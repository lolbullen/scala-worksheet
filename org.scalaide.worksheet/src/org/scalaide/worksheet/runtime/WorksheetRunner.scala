package org.scalaide.worksheet.runtime

import scala.collection.mutable.Publisher
import scala.collection.mutable.Subscriber

import org.scalaide.core.BuildSuccess
import org.scalaide.core.IScalaProject
import org.scalaide.core.IScalaProjectEvent
import org.scalaide.logging.HasLogger
import org.scalaide.util.ui.DisplayThread
import org.scalaide.worksheet.ScriptCompilationUnit
import org.scalaide.worksheet.WorksheetPlugin
import org.scalaide.worksheet.editor.DocumentHolder

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props

object WorksheetRunner {

  private[runtime] def props(scalaProject: IScalaProject): Props =
    Props(new WorksheetRunner(scalaProject))

  case class RunEvaluation(unit: ScriptCompilationUnit, editor: DocumentHolder)
  case object RefreshResidentCompiler

  class BuildListener(worksheetRunner: ActorRef) extends Subscriber[IScalaProjectEvent, Publisher[IScalaProjectEvent]] {
    override def notify(pub: Publisher[IScalaProjectEvent], event: IScalaProjectEvent) = {
      event match {
        case e: BuildSuccess => worksheetRunner ! RefreshResidentCompiler
      }
    }
  }
}

/** An evaluator for worksheet documents.
  *
  * It evaluates the contents of the given document and returns the output of the
  * instrumented program.
  *
  * It instantiates the instrumented program in-process, using a different class-loader.
  * A more advanced evaluator would spawn a new VM, to allow debugging in the future.
  */
private class WorksheetRunner private (scalaProject: IScalaProject) extends Actor with HasLogger {
  import WorksheetRunner._
  import ResidentCompiler._

  private val config = Configuration(scalaProject)
  private val instrumenter = new SourceInstrumenter(config)
  private var compiler = ResidentCompiler(scalaProject, config)
  private var executor: ActorRef = _
  private var buildListener: BuildListener = _

  override def preStart(): Unit = {
    buildListener = new BuildListener(self)
    scalaProject.subscribe(buildListener)
    executor = context.actorOf(ProgramExecutor.props())
  }

  override def postStop(): Unit = {
    scalaProject.removeSubscription(buildListener)
  }

  override def receive = {
    case RunEvaluation(unit, editor) =>
      unit.clearBuildErrors()

      editor.clearResults()

      instrumenter.instrument(unit) match {
        case Left(ex) => eclipseLog.error("Error during instrumentation of " + unit, ex)
        case Right((decl, source)) =>
          compiler.compile(source) match {
            case CompilationFailed(errors) =>
              logger.debug("compilation errors in " + (unit.file.name) + ": " + errors.mkString(","))
              editor.endUpdate()
              // Fix the race condition in error markers by updating them
              // on the UI thread. Otherwise, the 'replaceWith' call before
              // might remove all markers, considering their positions 'deleted'
              // by the replace action
              DisplayThread.asyncExec { reportBuildErrors(unit, errors) }

            case CompilationSuccess =>
              executor ! ProgramExecutor.RunProgram(unit, decl.fullName, classpath, editor)
          }
      }

    case msg: ProgramExecutor.StopRun =>
      executor forward msg

    case RefreshResidentCompiler =>
      compiler = ResidentCompiler(scalaProject, config)
  }

  /** The classpath, as a list of local filesystem path
    */
  private def classpath: Seq[String] = {
    WorksheetPlugin.worksheetLibrary.map(_.toOSString()).toSeq ++
      scalaProject.scalaClasspath.fullClasspath.map(_.getAbsolutePath()) ++
      scalaProject.outputFolderLocations.map(_.toOSString()) :+ config.binFolder.getAbsolutePath()
  }

  private def reportBuildErrors(unit: ScriptCompilationUnit, errors: Iterable[CompilationError]): Unit = {
    errors map { error => unit.reportBuildError(error.msg, error.pos) }
  }

  override def toString: String = "WorksheetEvaluator <actor>"
}
