package com.mcqapp

import com.mcqapp.data.repository.BrowseListFilter
import com.mcqapp.ui.browse.BrowseFilterSnapshot
import com.mcqapp.ui.browse.BrowseFilterState
import com.mcqapp.ui.browse.planBrowseFilterRestore
import com.mcqapp.ui.browse.restoreBrowseFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B7 — query and filter chips survive process death. The saved snapshot wins
 * over the freshly created (route-seeded) VM state; with nothing saved the
 * route's initial category filter is kept.
 */
class Round2BrowsePersistenceTest {

    private val routeSeeded = BrowseFilterState(
        query = "",
        filter = BrowseListFilter.ALL,
        categoryFilterIds = setOf("from-route")
    )

    @Test
    fun savedChipsAreRestoredAfterProcessDeath() {
        val saved = BrowseFilterSnapshot(
            query = "biology",
            filter = BrowseListFilter.NO_ANSWER,
            categoryFilterIds = setOf("saved-a", "saved-b")
        )
        val restored = restoreBrowseFilters(saved, routeSeeded)
        assertEquals("biology", restored.query)
        assertEquals(BrowseListFilter.NO_ANSWER, restored.filter)
        assertEquals(setOf("saved-a", "saved-b"), restored.categoryFilterIds)
    }

    @Test
    fun withNothingSavedTheRouteSeededFilterIsKept() {
        val restored = restoreBrowseFilters(null, routeSeeded)
        assertEquals(routeSeeded, restored)
    }

    @Test
    fun anExplicitlyClearedSnapshotWinsOverTheSeed() {
        // The user really did clear the chips: saved-empty is a real state and
        // must not be mistaken for "nothing saved" and re-seeded from the route.
        val saved = BrowseFilterSnapshot("", BrowseListFilter.ALL, emptySet())
        val restored = restoreBrowseFilters(saved, routeSeeded)
        assertEquals(emptySet<String>(), restored.categoryFilterIds)
        assertEquals("", restored.query)
    }

    @Test
    fun snapshotOfStateRoundTripsEveryField() {
        val state = BrowseFilterState("q", BrowseListFilter.NO_EXPLANATION, setOf("c"))
        assertEquals(state, restoreBrowseFilters(BrowseFilterSnapshot.of(state), routeSeeded))
    }

    private val savedChips = BrowseFilterSnapshot(
        "biology", BrowseListFilter.NO_ANSWER, setOf("saved-a")
    )

    @Test
    fun processDeathAppliesSavedChipsToAFreshViewModel() {
        // New VM, recreated from the route ⇒ only the seed, while the saved
        // snapshot came back from saved state. Every field must be written.
        val freshVm = routeSeeded
        val plan = planBrowseFilterRestore(savedChips, freshVm)
        assertTrue(plan.hasChanges)
        assertEquals("biology", plan.query)
        assertEquals(BrowseListFilter.NO_ANSWER, plan.filter)
        assertEquals(setOf("saved-a"), plan.categoryFilterIds)
    }

    @Test
    fun rotationWithTheSameViewModelAppliesNothing() {
        // The VM survived and already holds the filters; the per-composition
        // guard replays once but must not write equal values (which would
        // rebuild the pager) nor lose the snapshot.
        val survivingVm = restoreBrowseFilters(savedChips, routeSeeded)
        val plan = planBrowseFilterRestore(savedChips, survivingVm)
        assertFalse(plan.hasChanges)
        assertNull(plan.query)
        assertNull(plan.filter)
        assertNull(plan.categoryFilterIds)
        assertEquals(savedChips, BrowseFilterSnapshot.of(survivingVm))
    }

    @Test
    fun rotationWithNoSavedStateLeavesTheRouteSeedAlone() {
        val plan = planBrowseFilterRestore(null, routeSeeded)
        assertFalse(plan.hasChanges)
        assertEquals(routeSeeded, restoreBrowseFilters(null, routeSeeded))
    }
}
