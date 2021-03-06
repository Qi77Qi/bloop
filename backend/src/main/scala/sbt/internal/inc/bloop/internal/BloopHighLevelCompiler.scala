// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop.internal

import java.io.File
import java.net.URI
import java.util.Optional
import java.util.concurrent.CompletableFuture

import monix.eval.Task
import sbt.internal.inc.JavaInterfaceUtil.EnrichOption
import sbt.internal.inc.bloop.{BloopZincCompiler, CompileMode, JavaSignal}
import sbt.internal.inc.javac.AnalyzingJavaCompiler
import sbt.internal.inc.{Analysis, AnalyzingCompiler, CompileConfiguration, CompilerArguments, MixedAnalyzingCompiler, ScalaInstance, Stamper, Stamps}
import sbt.util.{InterfaceUtil, Logger}
import xsbti.{AnalysisCallback, CompileFailed}
import xsbti.compile.{ClassFileManager, CompileOrder, DependencyChanges, IncOptions, IncToolOptions, MultipleOutput, Output, SingleOutput}

import scala.util.control.NonFatal

/**
 *
 * Defines a high-level compiler after [[sbt.internal.inc.MixedAnalyzingCompiler]], with the
 * exception that this one changes the interface to allow compilation to be task-based and
 * only proceed after external tasks signal it (see `startJavaCompilation` in `compile`).
 *
 * This change is paramount to get pipelined incremental compilation working.
 *
 * @param scalac The Scala compiler (this one takes the concrete implementation, not an interface).
 * @param javac The concrete Java compiler.
 * @param config The compilation configuration.
 * @param logger The logger.
 */
final class BloopHighLevelCompiler(
    scalac: AnalyzingCompiler,
    javac: AnalyzingJavaCompiler,
    config: CompileConfiguration,
    logger: Logger
) {
  private[this] final val setup = config.currentSetup
  private[this] final val classpath = config.classpath.map(_.getAbsoluteFile)

  private[this] val JavaCompleted: CompletableFuture[Unit] = {
    val cf = new CompletableFuture[Unit]()
    cf.complete(())
    cf
  }

  /**
   * Compile
   *
   * @param sourcesToCompile The source to (incrementally) compile at one time.
   * @param changes The dependency changes detected previously.
   * @param callback The analysis callback from the compiler.
   * @param classfileManager The classfile manager charged with the class files lifecycle.
   * @param startJavaCompilation A task that will end whenever Java compilation can start.
   * @return
   */
  def compile(
      sourcesToCompile: Set[File],
      changes: DependencyChanges,
      callback: AnalysisCallback,
      classfileManager: ClassFileManager,
      compileMode: CompileMode
  ): Task[Unit] = {
    def timed[T](label: String, log: Logger)(t: => T): T = {
      val start = System.nanoTime
      val result = t
      val elapsed = System.nanoTime - start
      log.debug(label + " took " + (elapsed / 1e6) + " ms")
      result
    }

    val outputDirs = {
      setup.output match {
        case single: SingleOutput => List(single.getOutputDirectory)
        case mult: MultipleOutput => mult.getOutputGroups.iterator.map(_.getOutputDirectory).toList
      }
    }

    outputDirs.foreach { d =>
      if (!d.getPath.endsWith(".jar") && !d.exists())
        sbt.io.IO.createDirectory(d)
    }

    val includedSources = config.sources.filter(sourcesToCompile)
    val (javaSources, scalaSources) = includedSources.partition(_.getName.endsWith(".java"))
    logInputs(logger, javaSources.size, scalaSources.size, outputDirs)

    // Note `pickleURI` has already been used to create the analysis callback in `BloopZincCompiler`
    val (pipeline: Boolean, batches: Option[Int], completeJava: CompletableFuture[Unit], fireJavaCompilation: Task[JavaSignal]) = {
      compileMode match {
        case CompileMode.Sequential => (false, None, JavaCompleted, Task.now(JavaSignal.ContinueCompilation))
        case CompileMode.Parallel(batches) => (false, Some(batches), JavaCompleted, Task.now(JavaSignal.ContinueCompilation))
        case CompileMode.Pipelined(_, completeJava, fireJavaCompilation) => (true, None, completeJava, fireJavaCompilation)
        case CompileMode.ParallelAndPipelined(batches, _, completeJava, fireJavaCompilation) => (true, Some(batches), completeJava, fireJavaCompilation)
      }
    }

    val compileScala: Task[Unit] = {
      if (scalaSources.isEmpty) Task.now(())
      else {
        val isDotty = ScalaInstance.isDotty(scalac.scalaInstance.actualVersion())
        val sources = if (setup.order == CompileOrder.Mixed) includedSources else scalaSources
        val cargs = new CompilerArguments(scalac.scalaInstance, config.classpathOptions)
        def compileSources(
            sources: Seq[File],
            scalacOptions: Array[String],
            callback: AnalysisCallback,
            picklepath: Seq[URI]
        ): Unit = {
          try {
            val args = cargs.apply(Nil, classpath, None, scalacOptions).toArray
            // Dotty doesn't yet support pipelining
            val normalSetup = isDotty || !pipeline
            if (normalSetup) scalac.compile(sources.toArray, changes, args, setup.output, callback, config.reporter, config.cache, logger, config.progress.toOptional)
            else scalac.compileAndSetUpPicklepath(sources.toArray, picklepath.toArray, changes, args, setup.output, callback, config.reporter, config.cache, logger, config.progress.toOptional)
          } catch {
            case NonFatal(t) =>
              // If scala compilation happens, complete the java promise so that it doesn't block
              if (!completeJava.isDone)
                completeJava.completeExceptionally(t)
              throw t
          }
        }

        def compileInParallel(batches: Int): Task[Unit] = {
          val outlinePromise = new CompletableFuture[Optional[URI]]()
          val firstCompilation = Task {
            // Use an independent callback for outlining because the promise doesn't leak to the build tool
            val outlineCallback = BloopHighLevelCompiler.buildCallbackFor(setup.output, config.incOptions, outlinePromise)
            val scalacOptionsFirstPass = BloopHighLevelCompiler.prepareOptsForOutlining(setup.options.scalacOptions)
            val args = cargs.apply(Nil, classpath, None, scalacOptionsFirstPass).toArray
            timed("Scala compilation (outlining)", logger) {
              compileSources(sources, scalacOptionsFirstPass, outlineCallback, config.picklepath)
            }
          }

          import bloop.monix.Java8Compat.JavaCompletableFutureUtils
          val scalacOptions = setup.options.scalacOptions
          firstCompilation
            .flatMap(_ => Task.deferFutureAction(s => outlinePromise.asScala(s)))
            .flatMap { pickleURI =>
              InterfaceUtil.toOption(pickleURI) match {
                case Some(pickleURI) =>
                  val groups: List[Seq[File]] = {
                    val groupSize = scalaSources.size / batches
                    if (groupSize == 0) List(scalaSources)
                    else scalaSources.grouped(groupSize).toList
                  }

                  Task.gatherUnordered(
                    groups.map { scalaSourceGroup =>
                      Task {
                        timed("Scala compilation (parallel compilation)", logger) {
                          val sourceGroup = {
                            // Pass in the java sources to every group if order is mixed
                            if (setup.order != CompileOrder.Mixed) scalaSourceGroup
                            else scalaSourceGroup ++ javaSources
                          }
                          compileSources(sourceGroup, scalacOptions, callback, List(pickleURI))
                        }
                      }
                    }
                  )
                case None =>
                  sys.error("Fatal error: parallel compilation failed because outlining didn't extract pickle information.")
              }
            }
            .map(_ => ()) // Just drop the list of units
        }

        def compileSequentially: Task[Unit] = Task {
          val scalacOptions = setup.options.scalacOptions
          val args = cargs.apply(Nil, classpath, None, scalacOptions).toArray
          timed("Scala compilation", logger) {
            compileSources(sources, scalacOptions, callback, config.picklepath)
          }
        }

        batches match {
          case Some(batches) => compileInParallel(batches)
          case None => compileSequentially
        }
      }
    }

    // Note that we only start Java compilation when the task `startJavaCompilation` signals it
    val compileJava: Task[Unit] = Task {
      if (javaSources.isEmpty) {
        if (!completeJava.isDone)
          completeJava.complete(())
        ()
      } else {
        timed("Java compilation + analysis", logger) {
          val incToolOptions = IncToolOptions.of(
            Optional.of(classfileManager),
            config.incOptions.useCustomizedFileManager()
          )
          val javaOptions = setup.options.javacOptions.toArray[String]
          try {
            javac.compile(javaSources, javaOptions, setup.output, callback, incToolOptions, config.reporter, logger, config.progress)
            if (!completeJava.isDone)
              completeJava.complete(())
            ()
          } catch {
            case f: CompileFailed =>
              // Intercept and report manually because https://github.com/sbt/zinc/issues/520
              config.reporter.printSummary()
              completeJava.completeExceptionally(f)
              throw f
          }
        }
      }
    }

    val compilationTask = {
      // Complete the java exception if there are no java sources
      if (javaSources.isEmpty)
        completeJava.complete(())
      fireJavaCompilation.flatMap {
        case JavaSignal.ContinueCompilation =>
          if (setup.order == CompileOrder.JavaThenScala) {
            compileJava.flatMap(_ => compileScala)
          } else {
            compileScala.flatMap(_ => compileJava)
          }

        case JavaSignal.FailFastCompilation(failedProjects) =>
          throw new StopPipelining(failedProjects)
      }
    }

    compilationTask.map { _ =>
      // TODO(jvican): Fix https://github.com/scalacenter/bloop/issues/386 here
      if (javaSources.size + scalaSources.size > 0)
        logger.info("Done compiling.")
    }
  }

  // TODO(jvican): Fix https://github.com/scalacenter/bloop/issues/386 here
  private[this] def logInputs(
      log: Logger,
      javaCount: Int,
      scalaCount: Int,
      outputDirs: Seq[File]
  ): Unit = {
    val scalaMsg = Analysis.counted("Scala source", "", "s", scalaCount)
    val javaMsg = Analysis.counted("Java source", "", "s", javaCount)
    val combined = scalaMsg ++ javaMsg
    if (combined.nonEmpty) {
      val targets = outputDirs.map(_.getAbsolutePath).mkString(",")
      log.info(combined.mkString("Compiling ", " and ", s" to $targets ..."))
    }
  }
}

object BloopHighLevelCompiler {
  private val OutlineCompileOptions: Array[String] =
    Array("-Ygenerate-pickles", "-Youtline", "-Ystop-after:picklergen")
  private val NonFriendlyCompileOptions: Array[String] =
    Array("-Ywarn-dead-code", "-Ywarn-numeric-widen", "-Ywarn-value-discard", "-Ywarn-unused-import")

  def prepareOptsForOutlining(opts: Array[String]): Array[String] = {
    val newOpts = opts.filterNot(o => NonFriendlyCompileOptions.contains(o) || o.startsWith("-Xlint"))
    newOpts ++ OutlineCompileOptions
  }

  def buildCallbackFor(
      output: Output,
      options: IncOptions,
      promise: CompletableFuture[Optional[URI]]
  ): AnalysisCallback = {
    val stamps = Stamps.initial(Stamper.forLastModified, Stamper.forHash, Stamper.forLastModified)
    import sbt.internal.inc.AnalysisCallback.{Builder => CallbackBuilder}
    new CallbackBuilder(_ => None, _ => Set.empty, (_, _) => None, stamps, output, options, promise).build()
  }

  def apply(config: CompileConfiguration, log: Logger): BloopHighLevelCompiler = {
    val (searchClasspath, entry) = MixedAnalyzingCompiler.searchClasspathAndLookup(config)
    val scalaCompiler = config.compiler.asInstanceOf[AnalyzingCompiler]
    val javaCompiler = new AnalyzingJavaCompiler(config.javac, config.classpath, config.compiler.scalaInstance, config.classpathOptions, entry, searchClasspath)
    new BloopHighLevelCompiler(scalaCompiler, javaCompiler, config, log)
  }
}
