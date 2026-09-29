package com.mcqapp

import com.mcqapp.domain.SubmitSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SubmitSummaryTest {

    @Test
    fun cleanAttemptShowsOnlyAnsweredLine() {
        val lines = SubmitSummary.lines(SubmitSummary.Summary(10, 10, 0, 0))
        assertEquals(listOf("Answered: 10 of 10"), lines)
    }

    @Test
    fun warningsAppearOnlyWhenRelevant() {
        val lines = SubmitSummary.lines(SubmitSummary.Summary(6, 10, 2, 1))
        assertEquals(
            listOf(
                "Answered: 6 of 10",
                "Unanswered: 4 — these score 0",
                "Flagged for review: 2",
                "Not scored (no answer key): 1"
            ),
            lines
        )
    }

    @Test
    fun unansweredDerivedFromTotal() {
        val summary = SubmitSummary.Summary(answered = 3, total = 5, flagged = 0, ungraded = 0)
        assertEquals(2, summary.unanswered)
    }

    @Test
    fun longestDwellLineAppearsWhenTracked() {
        val lines = SubmitSummary.lines(
            SubmitSummary.Summary(5, 5, 0, 0, slowestQuestion = 7, slowestSeconds = 252)
        )
        assertEquals(
            listOf("Answered: 5 of 5", "Longest on Q7: 4:12"),
            lines
        )
    }
}
