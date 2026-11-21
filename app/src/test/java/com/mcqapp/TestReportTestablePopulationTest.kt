package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.ReportRow
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestReportBuilder
import com.mcqapp.domain.TestReportInput
import com.mcqapp.domain.TestStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The result report measures its population from the questions a test can
 * sample. A flashcard is study-only, so a raw category count would promise
 * coverage, a tighter margin and a longer-mock target the run can never reach.
 *
 * [TestReportInput.testableScopeSize] / `testableCategoryCounts` are the
 * testable figures the Results screen reads; without them the report keeps the
 * raw fallback (old callers, synthetic inputs).
 */
class TestReportTestablePopulationTest {

    private fun attempt() = Attempt(
        id = 1L,
        paperId = "p1",
        title = "Paper",
        totalQuestions = 20,
        correctCount = 10,
        wrongCount = 10,
        skippedCount = 0,
        score = 10.0,
        maxScore = 20.0,
        durationSeconds = 1_200,
        finishedAt = 1_000L,
        testType = "RANDOM",
        requestedCount = 20,
        scopeKey = ScopeKey.wholePaper("p1"),
        samplingVersion = TestPlan.SAMPLING_VERSION,
        exclusionMode = ExclusionMode.NONE.name
    )

    private fun row(index: Int, correct: Boolean) = ReportRow(
        questionId = "q$index",
        categoryTitle = "Polity",
        currentCategoryId = "c1",
        dwellSeconds = 30,
        isCorrect = correct,
        graded = true,
        answered = true
    )

    /** 20 rows, all in `c1`, half right: accuracy 0.5. */
    private fun rows(): List<ReportRow> =
        (1..20).map { row(it, correct = it <= 10) }

    /**
     * `c1` holds 34 multiple-choice questions (all sampled), `c2` holds 15
     * multiple-choice among 35 raw rows (unsampled), `c3` is card-only: 5 raw
     * questions and nothing testable. Testable = 49; raw = 74.
     */
    private fun paper() = Paper(
        id = "p1",
        title = "Paper",
        negativeMarking = 0.0,
        categories = listOf(
            CategoryNode("c1", "p1", "Polity", null, questionCount = 34),
            CategoryNode("c2", "p1", "Economy", null, questionCount = 35),
            CategoryNode("c3", "p1", "Cards only", null, questionCount = 5)
        )
    )

    private fun build(testable: Boolean) = TestReportBuilder.build(
        TestReportInput(
            attempt = attempt(),
            rows = rows(),
            paper = paper(),
            testableScopeSize = if (testable) 49 else null,
            testableCategoryCounts = if (testable) {
                mapOf("c1" to 34, "c2" to 15)
            } else {
                emptyMap()
            }
        )
    )

    @Test
    fun thePopulationCoverageAndMarginUseTheTestableCount() {
        val report = build(testable = true)
        // 34 + 15 testable; the 5 card-only questions do not count.
        assertEquals(49, report.scopeSize)
        assertEquals(49, report.coverage!!.scopeSize)
        assertEquals(29, report.coverage!!.unseenRemaining)
        assertEquals(
            "the margin uses the testable finite population",
            TestStatistics.marginPoints(0.5, 20, 49)!!,
            report.marginPoints!!,
            0.0001
        )
        // A card-only category cannot be "unseen": nothing there is testable.
        assertFalse(report.notSampledTopics.contains("Cards only"))
        assertTrue("the unsampled testable category is still named", report.notSampledTopics.contains("Economy"))
    }

    @Test
    fun theLongerMockSuggestionIsCappedByTheTestableSupply() {
        val report = build(testable = true)
        val action = report.actions.firstOrNull {
            it.rule is com.mcqapp.domain.RecommendedAction.Rule.TakeLongerMock
        }
        assertTrue("a wide margin over 49 testable questions suggests a longer mock", action != null)
        // maxAllowedFor(49) == 40; the raw 74-question scope would have
        // suggested 50, a run the paper cannot supply.
        assertEquals(40, action!!.testCount)
    }

    @Test
    fun withoutTheTestableReadsTheReportKeepsItsRawFallback() {
        val report = build(testable = false)
        assertEquals(74, report.scopeSize)
        assertTrue(report.notSampledTopics.contains("Economy"))
    }
}
