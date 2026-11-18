package com.mcqapp

import com.mcqapp.domain.ReportFamily
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.Trends
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10/R5: the History trend helpers kept pure so both the category-title
 * resolution and the spoken delta direction can be tested without Compose.
 */
class Round2ResultsTrendsTest {

    private fun trend(
        scopeCategoryIds: String = "41,42",
        scopeKey: String = "scope::custom"
    ) = Trends.FamilyTrend(
        key = "k",
        paperId = "p1",
        title = "Paper One",
        family = ReportFamily.MOCK,
        type = null,
        requestedCount = 10,
        freshSample = false,
        scopeKey = scopeKey,
        scopeCategoryIds = scopeCategoryIds,
        variant = null,
        attempts = 3,
        latestPercent = 80.0,
        bestPercent = 90.0,
        deltaPoints = 5.0
    )

    @Test
    fun scopeSuffixUsesCategoryTitlesInsteadOfRawIds() {
        val suffix = trend().scopeSuffix(mapOf("41" to "Physics", "42" to "Chemistry"))
        assertEquals("Physics, Chemistry", suffix)
        assertTrue("no opaque id may remain", !suffix!!.contains("41"))
    }

    @Test
    fun deletedCategoryFallsBackToItsIdButKnownOnesStillResolve() {
        val suffix = trend("41,42,43").scopeSuffix(
            mapOf("41" to "Physics", "42" to "Chemistry")
        )
        assertEquals("Physics, Chemistry, 43", suffix)
    }

    @Test
    fun truncationKeepsThreeTitlesAndCountsTheRest() {
        val suffix = trend("1,2,3,4,5").scopeSuffix(
            mapOf("1" to "A", "2" to "B", "3" to "C", "4" to "D", "5" to "E")
        )
        assertEquals("A, B, C +2", suffix)
    }

    @Test
    fun wholePaperHasNoScopeSuffixEvenWithATitleMap() {
        val whole = trend(scopeKey = ScopeKey.wholePaper("p1"))
        assertNull(whole.scopeSuffix(mapOf("41" to "Physics")))
    }

    @Test
    fun deltaDirectionCoversImprovedDeclinedAndTheDeadBand() {
        assertEquals(Trends.Delta.IMPROVED, Trends.deltaDirection(5.0))
        assertEquals(Trends.Delta.DECLINED, Trends.deltaDirection(-5.0))
        // Equal boundaries and anything inside them are unchanged (no spoken
        // "improved" for a rounding-level wobble).
        assertEquals(Trends.Delta.UNCHANGED, Trends.deltaDirection(0.005))
        assertEquals(Trends.Delta.UNCHANGED, Trends.deltaDirection(-0.005))
        assertEquals(Trends.Delta.UNCHANGED, Trends.deltaDirection(0.0))
    }
}
