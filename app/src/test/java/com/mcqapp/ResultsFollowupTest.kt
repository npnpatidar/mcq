package com.mcqapp

import com.mcqapp.data.export.ReportPdf
import com.mcqapp.domain.Attempt
import com.mcqapp.domain.ExamTiming
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.RecommendedAction
import com.mcqapp.domain.RelaunchPlanner
import com.mcqapp.domain.ReportRow
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestReportBuilder
import com.mcqapp.domain.TestReportInput
import com.mcqapp.ui.results.ExportGate
import com.mcqapp.ui.results.OptionState
import com.mcqapp.ui.results.optionStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The five results follow-up fixes. The pure seams (PDF layout, relaunch plan,
 * export gate, option state) are asserted by value; the two Compose-only fixes
 * (in-place Retry, merged option semantics) are pinned structurally because
 * this module has no Compose test harness and no new test dependency is allowed.
 */
class ResultsFollowupTest {

    private fun screenSource(): String =
        File("src/main/java/com/mcqapp/ui/results/ResultsScreen.kt").readText()

    private fun attempt(
        scopeCategoryIds: String = "",
        exclusion: String = ExclusionMode.NONE.name,
        variant: String? = null
    ) = Attempt(
        id = 7L,
        paperId = "p1",
        title = "Polity Paper",
        totalQuestions = 4,
        correctCount = 1,
        wrongCount = 1,
        skippedCount = 2,
        score = 1.0,
        maxScore = 4.0,
        durationSeconds = 120,
        finishedAt = 1_700_000_000_000L,
        testType = "RANDOM",
        requestedCount = 20,
        scopeCategoryIds = scopeCategoryIds,
        scopeKey = ScopeKey.wholePaper("p1"),
        samplingVersion = TestPlan.SAMPLING_VERSION,
        exclusionMode = exclusion,
        variant = variant
    )

    private fun row(
        id: String,
        correct: Boolean,
        answered: Boolean,
        categoryId: String?,
        title: String = if (categoryId == "c1") "Polity" else "Economy"
    ) = ReportRow(
        questionId = id,
        categoryTitle = title,
        currentCategoryId = categoryId,
        dwellSeconds = 10,
        isCorrect = correct,
        graded = true,
        answered = answered
    )

    // 5 — PDF denominator.

    @Test
    fun pdfScoreUsesAnsweredDenominatorInBothFractionAndPercentage() {
        val report = TestReportBuilder.build(
            TestReportInput(
                attempt = attempt(),
                rows = listOf(
                    row("a", correct = true, answered = true, categoryId = "c1"),
                    row("b", correct = false, answered = true, categoryId = "c1"),
                    row("c", correct = false, answered = false, categoryId = "c2"),
                    row("d", correct = false, answered = false, categoryId = "c2")
                ),
                paper = Paper("p1", "Polity Paper")
            )
        )
        val text = ReportPdf.lines(report).map { it.text }
        // 4 shown but only 2 answered: old code printed "1/4 correct · 50%".
        assertTrue(
            "fraction and percentage must share the answered denominator: $text",
            text.contains("1/2 correct · 50% accuracy")
        )
        assertFalse(text.any { it.contains("1/4 correct") })
    }

    // 2 — relaunch plan.

    @Test
    fun relaunchPlanForwardsTheOriginalScopeExclusionVariantAndMinutes() {
        val plan = RelaunchPlanner.from(
            attempt(
                scopeCategoryIds = "c1,c2",
                exclusion = ExclusionMode.RECENT_3.name,
                variant = "Polity"
            )
        )
        assertEquals("p1", plan.paperId)
        assertEquals(listOf("c1", "c2"), plan.scopeCategoryIds)
        assertEquals(ExclusionMode.RECENT_3.name, plan.exclusionMode)
        assertEquals("Polity", plan.variant)
        // Attempts store no configured minutes; the paper's own duration is
        // preserved rather than a minute value invented at relaunch time.
        assertEquals(ExamTiming.USE_PAPER_DURATION, plan.minutes)
    }

    @Test
    fun anUnresolvedCategoryFallsBackToTheRecordedScopeNeverTheWholePaper() {
        // A weak topic whose questions moved away has currentCategoryId == null,
        // so studyWeakest produces empty ids. The relaunch must carry the
        // attempt's recorded scope, not an empty "whole paper" list.
        // Polity: 5/12 = 42% (weak); Economy: 4/5 = 80%; scope 9/17 = 53%, so
        // Polity clears the 10-point studyWeakest gap. Its currentCategoryId
        // is null while its title stays "Polity".
        val rows = buildList {
            repeat(5) {
                add(row("p$it", correct = true, answered = true, categoryId = null, title = "Polity"))
            }
            repeat(7) {
                add(row("pw$it", correct = false, answered = true, categoryId = null, title = "Polity"))
            }
            repeat(4) { add(row("e$it", correct = true, answered = true, categoryId = "c2")) }
            add(row("ew", correct = false, answered = true, categoryId = "c2"))
        }
        val report = TestReportBuilder.build(
            TestReportInput(
                attempt = attempt(scopeCategoryIds = "c1,c2"),
                rows = rows,
                paper = Paper("p1", "Polity Paper", categories = emptyList())
            )
        )
        val study = report.actions.firstOrNull {
            it.rule is RecommendedAction.Rule.StudyTopic
        }
        assertTrue("expected a StudyTopic action", study != null)
        val action = study!!
        assertEquals(emptyList<String>(), action.categoryIds)
        assertEquals(listOf("c1", "c2"), action.relaunch!!.scopeCategoryIds)
        assertTrue("must never widen to whole paper", action.relaunch!!.scopeCategoryIds.isNotEmpty())
    }

    @Test
    fun anActionWithConcreteCategoriesKeepsThemInTheRelaunchScope() {
        val plan = RelaunchPlanner.forAction(
            RecommendedAction(
                rule = RecommendedAction.Rule.KeepGoing,
                kind = RecommendedAction.Kind.TEST,
                categoryIds = listOf("c2")
            ),
            attempt(scopeCategoryIds = "c1,c2")
        )
        assertEquals(listOf("c2"), plan.scopeCategoryIds)
    }

    // 3 — export guard.

    @Test
    fun exportGateAllowsOnlyOneCallerUntilItIsReleased() {
        val gate = ExportGate()
        assertTrue("first export acquires", gate.tryAcquire())
        assertFalse("a double-tap is rejected", gate.tryAcquire())
        assertTrue(gate.isBusy)
        gate.release()
        assertFalse(gate.isBusy)
        assertTrue("a later export can run", gate.tryAcquire())
    }

    // 4 — option semantics.

    @Test
    fun optionStateDistinguishesCorrectChosenMissedWrongAndUngraded() {
        assertEquals(OptionState.CORRECT_CHOSEN, optionStateOf(true, true, false))
        assertEquals(OptionState.CORRECT_MISSED, optionStateOf(true, false, false))
        assertEquals(OptionState.WRONG_CHOSEN, optionStateOf(false, true, false))
        assertEquals(OptionState.NOT_CHOSEN, optionStateOf(false, false, false))
        assertEquals(OptionState.UNGRADED_CHOSEN, optionStateOf(false, true, true))
        assertEquals(OptionState.UNGRADED_NOT_CHOSEN, optionStateOf(false, false, true))
    }

    // 1 & 3 & 4 — Compose-only contracts, pinned structurally.

    @Test
    fun loadErrorKeepsTheTextAndOffersAnInPlaceRetry() {
        val block = screenSource()
            .substringAfter("state.loadError?.let")
            .substringBefore("val attempt = state.attempt")
        assertTrue("error text stays", "Text(error" in block)
        assertTrue("Retry calls the existing reload", "viewModel.reload()" in block)
        assertTrue("shared Retry label", "R.string.retry" in block)
    }

    @Test
    fun exportControlsGuardDoubleTapDisableAndShowProgress() {
        val source = screenSource()
        assertTrue("synchronous guard before launch", "if (exportRunning || state.exporting) return" in source)
        assertTrue("controls disabled while exporting", "enabled = !exportBusy" in source)
        assertTrue("visible progress state", "if (exportBusy) {" in source && "CircularProgressIndicator" in source)
    }

    @Test
    fun optionRowsMergeSpokenTextAndStateIntoOneNode() {
        val block = screenSource()
            .substringAfter("result.options.forEach")
            .substringBefore("if (result.explanationElements")
        assertTrue("merged semantics", "clearAndSetSemantics" in block)
        assertTrue("spoken description", "contentDescription = spoken" in block)
        assertTrue("option text is announced", "option.text" in block)
        assertTrue("every option gets a state", "optionStateOf(" in block)
    }

    @Test
    fun followUpStringsAreDeclared() {
        val strings = File("src/main/res/values/strings_followup_results.xml").readText()
        listOf(
            "report_export_in_progress", "option_spoken", "option_correct_chosen",
            "option_correct_missed", "option_wrong_chosen", "option_not_chosen",
            "option_ungraded_chosen"
        ).forEach { assertTrue("$it must be declared", "$it\"" in strings) }
    }
}
