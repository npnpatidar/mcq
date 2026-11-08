package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.RecommendedAction
import com.mcqapp.domain.ReportRow
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestReport
import com.mcqapp.domain.TestReportBuilder
import com.mcqapp.domain.TestReportInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each section 11.11 rule fires on its evidence and not otherwise, and no rule
 * invents a number the report does not hold.
 */
class RecommendationEngineTest {

    private fun attempt(
        correct: Int = 12,
        wrong: Int = 4,
        skipped: Int = 2,
        score: Double = 12.0 - 4.0 / 3.0,
        maxScore: Double = 18.0
    ) = Attempt(
        id = 1L,
        paperId = "p1",
        title = "Paper",
        totalQuestions = 20,
        correctCount = correct,
        wrongCount = wrong,
        skippedCount = skipped,
        score = score,
        maxScore = maxScore,
        durationSeconds = 1_200,
        finishedAt = 1_000L,
        testType = "RANDOM",
        requestedCount = 20,
        scopeKey = ScopeKey.wholePaper("p1"),
        samplingVersion = TestPlan.SAMPLING_VERSION
    )

    private fun paper(marking: Double = 0.0) = Paper(
        id = "p1",
        title = "Paper",
        negativeMarking = marking,
        categories = listOf(
            CategoryNode("c1", "p1", "Polity", null, questionCount = 100)
        )
    )

    private fun row(
        id: String,
        correct: Boolean,
        answered: Boolean = true,
        dwell: Long = 40,
        title: String = "Polity"
    ) = ReportRow(
        questionId = id,
        categoryTitle = title,
        currentCategoryId = "c1",
        dwellSeconds = dwell,
        isCorrect = correct,
        graded = true,
        answered = answered
    )

    private fun report(
        attempt: Attempt = attempt(),
        rows: List<ReportRow>,
        marking: Double = 0.0
    ): TestReport = TestReportBuilder.build(
        TestReportInput(attempt = attempt, rows = rows, paper = paper(marking))
    )

    private fun rows(
        correct: Int,
        wrong: Int,
        skipped: Int,
        correctTitle: String = "Polity"
    ): List<ReportRow> = buildList {
        repeat(correct) { add(row("c$it", correct = true, title = correctTitle)) }
        repeat(wrong) { add(row("w$it", correct = false)) }
        repeat(skipped) { add(row("s$it", correct = false, answered = false, dwell = 0)) }
    }

    @Test
    fun skippedQuestionsWithNoMarkingRecommendAttemptingEveryQuestion() {
        val report = report(
            attempt = attempt(correct = 10, wrong = 1, skipped = 5, score = 10.0, maxScore = 16.0),
            rows = rows(correct = 10, wrong = 1, skipped = 5),
            marking = 0.0
        )
        val action = report.actions.firstOrNull {
            it.rule is RecommendedAction.Rule.AttemptEveryQuestion
        }
        assertTrue("expected the every-question rule", action != null)
        assertEquals(5, (action!!.rule as RecommendedAction.Rule.AttemptEveryQuestion).skipped)
    }

    @Test
    fun skippedQuestionsWithMarkingRecommendGuessingOnlyWithElimination() {
        val report = report(
            attempt = attempt(correct = 10, wrong = 1, skipped = 5, score = 10.0 - 1.0 / 3, maxScore = 16.0),
            rows = rows(correct = 10, wrong = 1, skipped = 5),
            marking = 1.0 / 3.0
        )
        val action = report.actions.firstOrNull {
            it.rule is RecommendedAction.Rule.GuessWhenPossible
        }
        assertTrue("expected the guess rule", action != null)
        assertEquals(5, (action!!.rule as RecommendedAction.Rule.GuessWhenPossible).skipped)
    }

    @Test
    fun aTopicTenPointsBelowTheScopeIsRecommendedForStudy() {
        // Polity 5/12 = 42%, Economy 4/5 = 80%; scope asked 9/17 = 53%.
        val rows = buildList {
            repeat(5) { add(row("p$it", correct = true, title = "Polity")) }
            repeat(7) { add(row("pw$it", correct = false, title = "Polity")) }
            repeat(4) { add(row("e$it", correct = true, title = "Economy")) }
            add(row("ew", correct = false, title = "Economy"))
        }
        val report = report(
            attempt = attempt(correct = 9, wrong = 8, skipped = 0, score = 9.0, maxScore = 17.0),
            rows = rows
        )
        val study = report.actions.firstOrNull { it.rule is RecommendedAction.Rule.StudyTopic }
        assertTrue("expected a study action", study != null)
        val rule = study!!.rule as RecommendedAction.Rule.StudyTopic
        assertEquals("Polity", rule.topicTitle)
        assertEquals(5, rule.correct)
        assertEquals(12, rule.asked)
        assertEquals(listOf("c1"), study.categoryIds)
    }

    @Test
    fun aTopicFivePointsBelowIsNotRecommendedForStudy() {
        // Polity 7/12 = 58%, Economy 4/5 = 80%; scope 11/17 = 65% — Polity is
        // 7 points below, under the ten-point bar.
        val rows = buildList {
            repeat(7) { add(row("p$it", correct = true, title = "Polity")) }
            repeat(5) { add(row("pw$it", correct = false, title = "Polity")) }
            repeat(4) { add(row("e$it", correct = true, title = "Economy")) }
            add(row("ew", correct = false, title = "Economy"))
        }
        val report = report(
            attempt = attempt(correct = 11, wrong = 6, skipped = 0, score = 11.0, maxScore = 17.0),
            rows = rows
        )
        assertTrue(
            report.actions.none { it.rule is RecommendedAction.Rule.StudyTopic }
        )
    }

    @Test
    fun aCleanAttemptFallsBackToKeepGoing() {
        val report = report(
            attempt = attempt(correct = 20, wrong = 0, skipped = 0, score = 20.0, maxScore = 20.0),
            rows = rows(correct = 20, wrong = 0, skipped = 0)
        )
        assertEquals(
            listOf(RecommendedAction.Rule.KeepGoing),
            report.actions.map { it.rule }
        )
        assertEquals(RecommendedAction.Kind.TEST, report.actions.single().kind)
    }

    @Test
    fun theEngineNeverExceedsFiveActions() {
        val report = report(
            attempt = attempt(correct = 6, wrong = 4, skipped = 6, score = 6.0, maxScore = 16.0),
            rows = rows(correct = 6, wrong = 4, skipped = 6)
        )
        assertTrue(report.actions.size in 1..5)
    }

    @Test
    fun theReviewRuleCarriesTheReportsOwnWrongCount() {
        val report = report(
            attempt = attempt(correct = 8, wrong = 5, skipped = 1, score = 8.0, maxScore = 14.0),
            rows = rows(correct = 8, wrong = 5, skipped = 1)
        )
        val review = report.actions.first {
            it.rule is RecommendedAction.Rule.ReviewWrong
        }
        assertEquals(
            report.score.wrong,
            (review.rule as RecommendedAction.Rule.ReviewWrong).count
        )
    }
}
