package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.Trends
import org.junit.Assert.assertEquals
import org.junit.Test

class TrendsTest {

    private fun attempt(
        id: Long,
        paperId: String,
        title: String,
        score: Double,
        maxScore: Double,
        finishedAt: Long
    ) = Attempt(
        id = id,
        paperId = paperId,
        title = title,
        totalQuestions = 10,
        correctCount = 0,
        wrongCount = 0,
        skippedCount = 0,
        score = score,
        maxScore = maxScore,
        durationSeconds = 0,
        finishedAt = finishedAt
    )

    @Test
    fun groupsByPaperWithLatestBestAndDelta() {
        val attempts = listOf(
            attempt(1, "p1", "Paper One", 4.0, 10.0, 1000),
            attempt(2, "p1", "Paper One", 9.0, 10.0, 3000),
            attempt(3, "p1", "Paper One", 6.0, 10.0, 2000),
            attempt(4, "p2", "Paper Two", 5.0, 5.0, 1500)
        )
        val trends = Trends.perPaper(attempts)
        assertEquals(2, trends.size)
        val p1 = trends.single { it.paperId == "p1" }
        assertEquals(3, p1.attempts)
        assertEquals(90.0, p1.latestPercent, 0.0001)
        assertEquals(90.0, p1.bestPercent, 0.0001)
        assertEquals(50.0, p1.deltaPoints, 0.0001)
        val p2 = trends.single { it.paperId == "p2" }
        assertEquals(1, p2.attempts)
        assertEquals(0.0, p2.deltaPoints, 0.0001)
    }

    @Test
    fun emptyAttemptsYieldNoTrends() {
        assertEquals(emptyList<Trends.PaperTrend>(), Trends.perPaper(emptyList()))
    }

    @Test
    fun negativeDeltaMeansRegression() {
        val attempts = listOf(
            attempt(1, "p1", "Paper", 8.0, 10.0, 1000),
            attempt(2, "p1", "Paper", 5.0, 10.0, 2000)
        )
        val trend = Trends.perPaper(attempts).single()
        assertEquals(-30.0, trend.deltaPoints, 0.0001)
        assertEquals(80.0, trend.bestPercent, 0.0001)
    }
}
