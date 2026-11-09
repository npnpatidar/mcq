package com.mcqapp

import com.mcqapp.domain.Attempt
import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.ReportFamily
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.stats.ExamCategoryTotal
import com.mcqapp.domain.stats.ExamDwellTotals
import com.mcqapp.domain.stats.ExamGroup
import com.mcqapp.domain.stats.ExamStatsBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure Exam aggregation (plan §3.1/§3.4). */
class ExamStatsTest {

    private val day = 86_400_000L

    private fun attempt(
        id: Long,
        type: String? = TestType.RANDOM.name,
        paperId: String = "p1",
        score: Double = 10.0,
        maxScore: Double = 20.0,
        finishedAt: Long = id * day,
        total: Int = 20,
        requested: Int = 20,
        correct: Int = 10,
        wrong: Int = 5,
        skipped: Int = 5,
        exclusion: String = ExclusionMode.NONE.name,
        scopeKey: String = ScopeKey.wholePaper(paperId),
        samplingVersion: Int = TestPlan.SAMPLING_VERSION,
        duration: Long = 600
    ) = Attempt(
        id = id,
        paperId = paperId,
        title = "Paper $paperId",
        totalQuestions = total,
        correctCount = correct,
        wrongCount = wrong,
        skippedCount = skipped,
        score = score,
        maxScore = maxScore,
        durationSeconds = duration,
        finishedAt = finishedAt,
        testType = type,
        requestedCount = requested,
        scopeKey = scopeKey,
        samplingVersion = samplingVersion,
        exclusionMode = exclusion
    )

    @Test
    fun emptyInputHasNoFigures() {
        val stats = ExamStatsBuilder.build(emptyList(), ExamGroup.MOCK_STRICT)
        assertEquals(0, stats.familyAttempts)
        assertEquals(0, stats.attempts)
        assertNull(stats.latestPercent)
        assertNull(stats.marginPoints)
        assertTrue(stats.trend.isEmpty())
        assertTrue(stats.recent.isEmpty())
        assertTrue(stats.isEmpty)
    }

    @Test
    fun headlineFiguresUseOneComparableGroup() {
        val attempts = listOf(
            attempt(1, score = 10.0, finishedAt = day, duration = 600),
            attempt(2, score = 16.0, finishedAt = 2 * day, duration = 1200)
        )
        val stats = ExamStatsBuilder.build(
            attempts = attempts,
            group = ExamGroup.MOCK_STRICT,
            dwell = ExamDwellTotals(totalSeconds = 300, timedRows = 20),
            population = 100
        )
        assertEquals(2, stats.attempts)
        assertEquals(2, stats.familyAttempts)
        assertEquals(80.0, stats.latestPercent!!, 0.0001)
        assertEquals(80.0, stats.bestPercent!!, 0.0001)
        assertEquals(65.0, stats.averagePercent!!, 0.0001)
        assertEquals(30.0, stats.deltaPoints!!, 0.0001)
        assertEquals(30, stats.totalMinutes)
        assertEquals(40, stats.shown)
        assertEquals(40, stats.requested)
        assertEquals(0.25, stats.skipRate!!, 0.0001)
        assertEquals(15.0, stats.averageSecondsPerQuestion!!, 0.0001)
        assertNotNull(stats.marginPoints)
        assertEquals(ExamGroup.MOCK_STRICT, stats.group)
    }

    @Test
    fun strictFreshAndPracticeAreNeverMixed() {
        val attempts = listOf(
            attempt(1, score = 10.0),
            attempt(
                2,
                score = 14.0,
                exclusion = ExclusionMode.RECENT_3.name
            ),
            attempt(3, type = TestType.WEAKEST_TOPICS.name, score = 4.0)
        )
        val strict = ExamStatsBuilder.build(attempts, ExamGroup.MOCK_STRICT)
        val fresh = ExamStatsBuilder.build(attempts, ExamGroup.MOCK_FRESH)
        val practice = ExamStatsBuilder.build(attempts, ExamGroup.PRACTICE)

        assertEquals(1, strict.familyAttempts)
        assertEquals(50.0, strict.latestPercent!!, 0.0001)
        assertEquals(1, fresh.familyAttempts)
        assertEquals(70.0, fresh.latestPercent!!, 0.0001)
        assertEquals(1, practice.familyAttempts)
        assertEquals(20.0, practice.latestPercent!!, 0.0001)
    }

    @Test
    fun randomAndBalancedStayInTheirOwnComparableKey() {
        val attempts = listOf(
            attempt(1, type = TestType.RANDOM.name, score = 10.0),
            attempt(2, type = TestType.RANDOM.name, score = 12.0),
            attempt(3, type = TestType.BALANCED.name, score = 16.0)
        )
        val stats = ExamStatsBuilder.build(attempts, ExamGroup.MOCK_STRICT)
        assertEquals(3, stats.familyAttempts)
        assertEquals(1, stats.attempts)
        assertEquals(TestType.BALANCED.name, stats.comparisonKey!!.testType)
        assertEquals(80.0, stats.latestPercent!!, 0.0001)
        assertEquals(1, stats.trend.size)
    }

    @Test
    fun legacyAttemptNeverEntersATrendOrHeadline() {
        val legacy = attempt(
            1,
            type = null,
            samplingVersion = 1,
            requested = 0,
            score = 2.0,
            finishedAt = day
        )
        val mock = attempt(2, score = 10.0, finishedAt = 2 * day)
        val stats = ExamStatsBuilder.build(listOf(legacy, mock), ExamGroup.MOCK_STRICT)

        assertEquals(1, stats.familyAttempts)
        assertEquals(1, stats.attempts)
        assertEquals(50.0, stats.latestPercent!!, 0.0001)
        assertEquals(1, stats.trend.size)
        assertTrue(stats.recent.any { it.family == ReportFamily.LEGACY })
        assertTrue(stats.recent.any { it.family == ReportFamily.MOCK })
    }

    @Test
    fun marginUsesShownCountAndIsAbsentForPractice() {
        val mock = attempt(1, score = 10.0, total = 20)
        val mockStats = ExamStatsBuilder.build(
            listOf(mock),
            ExamGroup.MOCK_STRICT,
            population = 100
        )
        assertTrue(mockStats.marginPoints!! > 0.0)

        val practice = attempt(2, type = TestType.WEAKEST_TOPICS.name, score = 10.0)
        val practiceStats = ExamStatsBuilder.build(
            listOf(practice),
            ExamGroup.PRACTICE,
            population = 100
        )
        assertNull(practiceStats.marginPoints)

        assertTrue(ExamStatsBuilder.marginPoints(50.0, 100, 100) == 0.0)
        assertNull(ExamStatsBuilder.marginPoints(50.0, 0, 100))
    }

    @Test
    fun categoryRowsAreWeakestFirstAndCappedButUnresolvedReconcile() {
        val totals = (0 until 20).map { index ->
            ExamCategoryTotal(
                title = "c$index",
                asked = 10,
                correct = index,
                unresolved = if (index == 19) 2 else 0
            )
        }
        val stats = ExamStatsBuilder.build(
            listOf(attempt(1)),
            ExamGroup.MOCK_STRICT,
            categoryTotals = totals,
            categoryLimit = 15
        )
        assertEquals(15, stats.categories.size)
        assertTrue(stats.categories.first().accuracy <= stats.categories.last().accuracy)
        assertEquals("c0", stats.categories.first().title)
        assertEquals(2, stats.unresolvedCategories)
    }

    @Test
    fun dailyTrendKeepsEmptyBuckets() {
        val attempts = listOf(
            attempt(1, score = 10.0, finishedAt = 0),
            attempt(2, score = 16.0, finishedAt = 10 * day)
        )
        val stats = ExamStatsBuilder.build(attempts, ExamGroup.MOCK_STRICT)
        assertEquals(11, stats.trend.size)
        assertEquals(1, stats.trend.first().attempts)
        assertEquals(0, stats.trend[1].attempts)
        assertNull(stats.trend[1].percent)
        assertEquals(80.0, stats.trend.last().percent!!, 0.0001)
    }

    @Test
    fun weeklyAndMonthlyTrendsChangeGranularity() {
        val weekly = ExamStatsBuilder.build(
            listOf(
                attempt(1, finishedAt = 0),
                attempt(2, finishedAt = 40 * day)
            ),
            ExamGroup.MOCK_STRICT
        )
        assertEquals(6, weekly.trend.size)

        val monthly = ExamStatsBuilder.build(
            listOf(
                attempt(1, finishedAt = 0),
                attempt(2, finishedAt = 400 * day)
            ),
            ExamGroup.MOCK_STRICT
        )
        assertTrue(monthly.trend.size in 2..14)
        assertTrue(monthly.trend.first().label.contains("Jan"))
    }

    @Test
    fun papersAreSplitOnlyInAnAllPapersScope() {
        val onePaper = listOf(attempt(1), attempt(2))
        assertTrue(
            ExamStatsBuilder.build(onePaper, ExamGroup.MOCK_STRICT).papers.isEmpty()
        )

        val twoPapers = listOf(
            attempt(1, paperId = "p1"),
            attempt(2, paperId = "p2")
        )
        val stats = ExamStatsBuilder.build(twoPapers, ExamGroup.MOCK_STRICT)
        assertEquals(2, stats.papers.size)
        assertEquals(setOf("p1", "p2"), stats.papers.map { it.paperId }.toSet())
        assertTrue(stats.papers.all { it.attempts == 1 })
    }

    @Test
    fun recentListIsNewestFirstWithFamilyBadges() {
        val attempts = listOf(
            attempt(1, score = 10.0, finishedAt = day),
            attempt(2, type = TestType.WEAKEST_TOPICS.name, score = 4.0, finishedAt = 2 * day),
            attempt(
                3,
                type = null,
                samplingVersion = 1,
                requested = 0,
                score = 2.0,
                finishedAt = 3 * day
            )
        )
        val stats = ExamStatsBuilder.build(attempts, ExamGroup.MOCK_STRICT)
        assertEquals(listOf(3L, 2L, 1L), stats.recent.map { it.attemptId })
        assertEquals(ReportFamily.LEGACY, stats.recent[0].family)
        assertEquals(ReportFamily.PRACTICE, stats.recent[1].family)
        assertEquals(ReportFamily.MOCK, stats.recent[2].family)
    }

    @Test
    fun comparableAttemptsAreTheLatestFullKeyOnly() {
        val attempts = listOf(
            attempt(1, requested = 20),
            attempt(2, requested = 50),
            attempt(3, requested = 50)
        )
        val comparable = ExamStatsBuilder.comparableAttempts(attempts, ExamGroup.MOCK_STRICT)
        assertEquals(listOf(2L, 3L), comparable.map { it.id })
        assertFalse(comparable.any { it.requestedCount == 20 })
    }
}
