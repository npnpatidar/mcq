package com.mcqapp

import com.mcqapp.domain.DailyGoal
import com.mcqapp.domain.stats.StatsAggregator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily goal is personal, not per-paper. Statistics can filter its charts
 * to one paper, but its gauge must show the same number as Library.
 *
 * Both ViewModels call [DailyGoal.todayReviews], so this fake overview reader
 * pins the shared rule: the request is always unscoped, even when the stats
 * page's selected paper would give a different count.
 */
class DailyGoalScopeTest {

    private class FakeOverview(val counts: Map<String?, Int>) {
        val scopes = mutableListOf<String?>()

        suspend fun reviews(
            scopePaperId: String?,
            range: StatsAggregator.Range,
            now: Long
        ): Int {
            assertEquals(StatsAggregator.Range.D30, range)
            assertTrue("a real clock is passed to the day boundary", now > 0L)
            scopes += scopePaperId
            return counts[scopePaperId] ?: 0
        }
    }

    @Test
    fun libraryAndStatsGoalCountsAgreeForAScopedStatsSelection() = runBlocking {
        val fake = FakeOverview(mapOf(null to 18, "paper-1" to 4))
        val selectedPaperId = "paper-1"

        // Library has no paper scope.
        val libraryCount = DailyGoal.todayReviews(
            load = { scope, range, at -> fake.reviews(scope, range, at) },
            now = 1L
        )
        // Statistics' charts use selectedPaperId; its goal must not.
        val statsCount = DailyGoal.todayReviews(
            load = { scope, range, at -> fake.reviews(scope, range, at) },
            now = 1L
        )

        assertEquals(18, libraryCount)
        assertEquals(18, statsCount)
        assertEquals(libraryCount, statsCount)
        assertNotEquals("the scoped chart count must not leak into the goal", 4, statsCount)
        assertNotEquals(
            "the selected paper has a different review count",
            fake.counts[selectedPaperId],
            statsCount
        )
        assertEquals("both screens request the global bucket", listOf<String?>(null, null), fake.scopes)
    }
}
