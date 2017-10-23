package org.scalaide.worksheet

import org.junit.runner.RunWith
import org.junit.runners.Suite

import org.scalaide.worksheet.completion.CompletionTests
import org.scalaide.worksheet.editor.EvaluationResultsAutoEditStrategyTest
import org.scalaide.worksheet.runtime.WorksheetEvalTest
import org.scalaide.worksheet.cross.MultiScalaVersionSuite

@RunWith(classOf[Suite])
@Suite.SuiteClasses(Array(
  classOf[WorksheetEvalTest],
  classOf[CompletionTests],
  classOf[EvaluationResultsAutoEditStrategyTest],
  classOf[MultiScalaVersionSuite]
))
class TestSuite