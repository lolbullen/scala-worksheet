package org.scalaide.worksheet.handlers

import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.handlers.HandlerUtil

import org.scalaide.logging.HasLogger
import org.scalaide.worksheet.editor.ScriptEditor

class EvalScript extends AbstractHandler with HasLogger {
  override def execute(event: ExecutionEvent): AnyRef = {
    HandlerUtil.getActiveEditor(event) match {
      case editor: ScriptEditor => editor.runEvaluation()
      case editor => logger.debug("Expected ScriptEditor, found "+editor)
    }
    null
  }
}