package org.scalaide.worksheet.editor

import org.eclipse.jdt.core.compiler.IProblem
import org.eclipse.jdt.internal.ui.javaeditor.CompilationUnitDocumentProvider.ProblemAnnotation
import org.eclipse.jdt.ui.PreferenceConstants
import org.eclipse.jdt.ui.actions.IJavaEditorActionDefinitionIds
import org.eclipse.jface.action.Action
import org.eclipse.jface.action.IMenuManager
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.text.Position
import org.eclipse.jface.text.source.Annotation
import org.eclipse.jface.text.source.IAnnotationModel
import org.eclipse.jface.text.source.IAnnotationModelExtension
import org.eclipse.jface.text.source.IAnnotationModelExtension2
import org.eclipse.jface.text.source.ISourceViewer
import org.eclipse.jface.util.PropertyChangeEvent
import org.eclipse.swt.SWT
import org.eclipse.swt.events.KeyAdapter
import org.eclipse.swt.events.KeyEvent
import org.eclipse.ui.editors.text.TextEditor
import org.eclipse.ui.texteditor.ITextEditorActionConstants
import org.eclipse.ui.texteditor.TextOperationAction
import org.scalaide.core.compiler.InteractiveCompilationUnit
import org.scalaide.logging.HasLogger

import org.scalaide.ui.editor.ISourceViewerEditor
import org.scalaide.ui.editor.InteractiveCompilationUnitEditor
import org.scalaide.ui.editor.SourceConfiguration
import org.scalaide.util.eclipse.EditorUtils
import org.scalaide.util.ui.DisplayThread
import org.scalaide.worksheet.ScriptCompilationUnit
import org.scalaide.worksheet.WorksheetPlugin
import org.scalaide.worksheet.editor.action.ClearResultsAction
import org.scalaide.worksheet.editor.action.RunEvaluationAction

object ScriptEditor {

  /** The annotation types showin when hovering on the left-side ruler (or in the status bar). */
  private[editor] val annotationsShownInHover = Set(
    "org.eclipse.jdt.ui.error", "org.eclipse.jdt.ui.warning", "org.eclipse.jdt.ui.info")

  val SCRIPT_EXTENSION = ".sc"

  val TAB_WIDTH = 2
}

/** A Scala script editor.*/
class ScriptEditor extends TextEditor with SelectionTracker with ISourceViewerEditor with InteractiveCompilationUnitEditor with HasLogger {
  private def prefStore = WorksheetPlugin.prefStore

  private lazy val sourceViewConfiguration = new ScriptConfiguration(prefStore, PreferenceConstants.getPreferenceStore, this)

  // constructor
  setSourceViewerConfiguration(sourceViewConfiguration)
  //  setPreferenceStore(prefStore)
  setPartName("Scala Script Editor")
  setDocumentProvider(new ScriptDocumentProvider)

  private class StopEvaluationOnKeyPressed(editorProxy: DefaultEditorProxy) extends KeyAdapter {
    val exitKeys = Set(SWT.DEL, SWT.BS, SWT.ESC)
    override def keyPressed(e: KeyEvent) {
      // Don't stop evaluation if no real character is entered in the editor (e.g., KEY UP/DOWN)
      if (Character.isLetterOrDigit(e.character)
        || Character.isWhitespace(e.character)
        || exitKeys(e.keyCode.toChar))
        stopEvaluation()
    }
  }

  /** This class is used by the `IncrementalDocumentMixer` to update the editor's document with
   *  the evaluation's result.
   */
  private class DefaultEditorProxy extends DocumentHolder {

    @volatile private[ScriptEditor] var ignoreDocumentUpdate = false
    private val stopEvaluationListener = new StopEvaluationOnKeyPressed(this)

    private def doc: IDocument = getDocumentProvider.getDocument(getEditorInput)

    override def getContents: String = doc.get

    override def replaceWith(content: String, revealPos: Int): Unit = {
      // we need to turn off evaluation on save if we don't want to loop forever
      // (because of `editorSaved` implementation, i.e., automatic worksheet evaluation on save)
      if (!ignoreDocumentUpdate) runInUi {
        val (line, col) = lineColumn(caretOffset)

        doc.set(content)
        getSourceViewer().getTextWidget().setCaretOffset(offsetOf(line, col))
        if (revealPos > 0) getSourceViewer().revealRange(revealPos, 0)
        getSourceViewer().invalidateTextPresentation()
      }
    }

    def lineColumn(offset: Int): (Int, Int) = {
      val region = doc.getLineInformationOfOffset(offset)
      (doc.getLineOfOffset(offset), offset - region.getOffset)
    }

    def offsetOf(line: Int, col: Int): Int = {
      val region1 = doc.getLineInformation(doc.getNumberOfLines() min line)
      region1.getOffset + (region1.getLength() min col)
    }

    private def caretOffset: Int = {
      var offset = -1
      DisplayThread.syncExec {
        // Read the comment in `runInUi`
        if (!disposed) offset = getSourceViewer().getTextWidget().getCaretOffset()
      }
      offset
    }

    override def beginUpdate(): Unit = {
      ignoreDocumentUpdate = false
      runInUi {
        getSourceViewer().getTextWidget().addKeyListener(stopEvaluationListener)
      }
    }

    override def endUpdate(): Unit = {
      stopExternalEditorUpdate()
      save()
    }

    private def save(): Unit = runInUi {
      evaluationOnSave = false
      doSave(null)
      evaluationOnSave = true
    }

    private[ScriptEditor] def stopExternalEditorUpdate(): Unit = {
      ignoreDocumentUpdate = true
      runInUi {
        getSourceViewer().getTextWidget().removeKeyListener(stopEvaluationListener)
      }
    }

    /** Run `f' in the UI thread and make sure we were not disposed before.
     *  Disable redraws during the execution of this block to reduce flickering.
     */
    private def runInUi(f: => Unit): Unit = DisplayThread.asyncExec {
      /* We need to make sure that the editor was not `disposed` before executing `f`
       * in the UI thread. The reason is that it is possible that after calling
       * `DisplayThread.asyncExec`, but before the passed closure is executed, the editor
       * may be disposed. If the editor is disposed, executing `f` will likely end up
       * throwing a NPE.
       *
       * FIXME:
       * Having said all the above, there is still a possible race condition, i.e., what
       * if `disposed` becomes true while `f` is being executed (since the two actions can
       * happen in parallel on two different threads). Well, this is indeed an issue, which
       * I fear need some thinking to be effectively fixed.
       */
      if (!disposed) {
        getSourceViewer().getTextWidget().setRedraw(false)
        try f finally getSourceViewer().getTextWidget().setRedraw(true)
      }
    }
  }

  @volatile private var evaluationOnSave = true
  @volatile private var disposed = false

  private val editorProxy = new DefaultEditorProxy

  override def handlePreferenceStoreChanged(event: PropertyChangeEvent) = {
    super.handlePreferenceStoreChanged(event)
    sourceViewConfiguration.propertyChange(event)
    getSourceViewer().invalidateTextPresentation()
  }

  override def initializeKeyBindingScopes() {
    setKeyBindingScopes(Array("org.scalaide.worksheet.editorScope"))
  }

  override def createActions() {
    super.createActions()

    // Adding source formatting action in the editor popup dialog
    val formatAction = new TextOperationAction(EditorMessages.resourceBundle, "Editor.Format.", this, ISourceViewer.FORMAT)
    formatAction.setActionDefinitionId("org.scalaide.worksheet.commands.format")
    setAction("format", formatAction)

    // Adding action to clear results in the editor popup dialog
    val clearResultsAction = new ClearResultsAction(this)
    clearResultsAction.setActionDefinitionId("org.scalaide.worksheet.commands.clearResults")
    setAction("clearResults", clearResultsAction)

    // Adding run evaluation action in the editor popup dialog
    val evalScriptAction = new RunEvaluationAction(this)
    evalScriptAction.setActionDefinitionId("org.scalaide.worksheet.commands.evalScript")
    setAction("evalScript", evalScriptAction)

    val openAction = new Action {
      private def scalaCompilationUnit: Option[ScriptCompilationUnit] =
        Option(getInteractiveCompilationUnit) map (_.asInstanceOf[ScriptCompilationUnit])

      override def run {
        scalaCompilationUnit foreach { scu =>
          val detector = SourceConfiguration.scalaDeclarationDetector
          detector.setContext(ScriptEditor.this)
          val region = EditorUtils.textSelection2region(getSelectionProvider.getSelection.asInstanceOf[ITextSelection])
          detector.detectHyperlinks(getSourceViewer, region, false) match {
            case Array(hyperlink) => hyperlink.open()
            case _ => () // didn't find it
          }
        }
      }
    }
    openAction.setActionDefinitionId(IJavaEditorActionDefinitionIds.OPEN_EDITOR)
    setAction("OpenEditor", openAction)
  }

  override def editorContextMenuAboutToShow(menu: IMenuManager) {
    super.editorContextMenuAboutToShow(menu)

    // add the format menu itemevalScript
    addAction(menu, ITextEditorActionConstants.GROUP_EDIT, "evalScript")
    addAction(menu, ITextEditorActionConstants.GROUP_EDIT, "format")
    addAction(menu, ITextEditorActionConstants.GROUP_EDIT, "clearResults")
  }

  type IAnnotationModelExtended = IAnnotationModel with IAnnotationModelExtension with IAnnotationModelExtension2

  /** Return the annotation model associated with the current document. */
  private def annotationModel: IAnnotationModelExtended = getDocumentProvider.getAnnotationModel(getEditorInput).asInstanceOf[IAnnotationModelExtended]

  def selectionChanged(selection: ITextSelection) {
    import scala.collection.JavaConverters.asScalaIteratorConverter
    val iterator = annotationModel.getAnnotationIterator(selection.getOffset, selection.getLength, true, true).asInstanceOf[java.util.Iterator[Annotation]]
    iterator.asScala.find(a => ScriptEditor.annotationsShownInHover(a.getType)).map(_.getText).foreach(setStatusLineErrorMessage)
  }

  def getViewer: ISourceViewer = getSourceViewer()

  override protected def editorSaved(): Unit = {
    super.editorSaved()
    //FIXME: Should be configurable
    if (evaluationOnSave) runEvaluation()
  }

  override def dispose(): Unit = {
    disposed = true
    stopEvaluation()
    super.dispose()
  }

  private[worksheet] def runEvaluation(): Unit = withScriptCompilationUnit {
    editorProxy.beginUpdate()

    import org.scalaide.worksheet.runtime.WorksheetRunner
    WorksheetPlugin.plugin.runtime ! WorksheetRunner.RunEvaluation(_, editorProxy)
  }

  private[worksheet] def clearResults(): Unit = {
    editorProxy.beginUpdate()
    editorProxy.clearResults()
    editorProxy.endUpdate()
  }

  private[worksheet] def stopEvaluation(): Unit = withScriptCompilationUnit {
    editorProxy.stopExternalEditorUpdate()

    import org.scalaide.worksheet.runtime.ProgramExecutor
    WorksheetPlugin.plugin.runtime ! ProgramExecutor.StopRun(_)
  }

  private def withScriptCompilationUnit(f: ScriptCompilationUnit => Unit): Unit = f(ScriptCompilationUnit.fromEditor(this))

  override def getInteractiveCompilationUnit(): InteractiveCompilationUnit = ScriptCompilationUnit.fromEditor(this)

  @volatile
  private var previousAnnotations = List[ProblemAnnotation]()

  def updateErrorAnnotations(errors: List[IProblem]) {
    def position(p: IProblem) = new Position(p.getSourceStart, p.getSourceEnd - p.getSourceStart + 1)

    val newAnnotations = for (e <- errors if !isPureExpressionWarning(e)) yield {
      val annotation = new ProblemAnnotation(e, null) // no compilation unit
      (annotation, position(e))
    }

    val newMap = newAnnotations.toMap
    import scala.collection.JavaConverters._
    annotationModel.replaceAnnotations(previousAnnotations.toArray, newMap.asJava)
    previousAnnotations = newAnnotations.map(_._1)

    // This shouldn't be necessary in @dragos' opinion. But see #84 and
    // http://stackoverflow.com/questions/12507620/race-conditions-in-annotationmodel-error-annotations-lost-in-reconciler
    DisplayThread.asyncExec { getSourceViewer.invalidateTextPresentation() }
  }

  private def isPureExpressionWarning(e: IProblem): Boolean =
    e.getMessage == "a pure expression does nothing in statement position; you may be omitting necessary parentheses"
}
