package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.ReportFamily
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestProgress
import com.mcqapp.domain.TestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Statistics Tests tab's pure aggregation (stats.md §13 phase 2). */
class TestProgressTest {

    private fun attempt(
        id: Long,
        type: String?,
        score: Double,
        finishedAt: Long,
        total: Int = 20,
        requested: Int = 20,
        exclusion: String = ExclusionMode.NONE.name
    ) = Attempt(
        id = id,
        paperId = "p1",
        title = "Paper",
        totalQuestions = total,
        correctCount = 10,
        wrongCount = 5,
        skippedCount = 5,
        score = score,
        maxScore = 20.0,
        durationSeconds = 600,
        finishedAt = finishedAt,
        testType = type,
        requestedCount = requested,
        scopeKey = ScopeKey.wholePaper("p1"),
        samplingVersion = TestPlan.SAMPLING_VERSION,
        exclusionMode = exclusion,
        targetTopicIds = if (type == TestType.WEAKEST_TOPICS.name) "c1,c2" else ""
    )

    @Test
    fun familiesAreAveragedSeparately() {
        val attempts = listOf(
            attempt(1, TestType.RANDOM.name, 10.0, 100),
            attempt(2, TestType.RANDOM.name, 16.0, 200),
            attempt(3, TestType.WEAKEST_TOPICS.name, 4.0, 300),
            attempt(4, TestType.RANDOM.name, 8.0, 400, total = 10)
        )
        val families = TestProgress.families(attempts)
        val mock = families.single { it.family == ReportFamily.MOCK }
        assertEquals(2, mock.attempts)
        assertEquals(65.0, mock.averagePercent, 0.0001)
        assertEquals(80.0, mock.latestPercent, 0.0001)
        assertEquals(1, families.single { it.family == ReportFamily.PRACTICE }.attempts)
        assertEquals(1, families.single { it.family == ReportFamily.SHORT }.attempts)
    }

    @Test
    fun practiceRunsKeepOnlyFocusAttemptsNewestFirst() {
        val attempts = listOf(
            attempt(1, TestType.WEAKEST_TOPICS.name, 4.0, 100),
            attempt(2, TestType.RANDOM.name, 8.0, 200),
            attempt(3, TestType.TOUGHEST_QUESTIONS.name, 5.0, 300),
            attempt(4, TestType.MISTAKES.name, 6.0, 400),
            attempt(5, TestType.EASIEST_QUESTIONS.name, 7.0, 500)
        )
        val runs = TestProgress.practiceRuns(attempts, limit = 2)
        assertEquals(listOf(5L, 3L), runs.map { it.id })
    }

    @Test
    fun aFreshSampleStaysInItsOwnFamily() {
        val attempts = listOf(
            attempt(1, TestType.RANDOM.name, 8.0, 100),
            attempt(
                2, TestType.RANDOM.name, 8.0, 200,
                exclusion = ExclusionMode.RECENT_3.name
            )
        )
        val families = TestProgress.families(attempts)
        assertEquals(2, families.size)
        assertTrue(families.any { it.family == ReportFamily.FRESH_SAMPLE })
    }
}
