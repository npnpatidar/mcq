package com.mcqapp

import com.mcqapp.domain.StudyScopeMenuChoice
import com.mcqapp.domain.studyScopeMenuSelection
import com.mcqapp.ui.navigation.decodeCategoryList
import com.mcqapp.ui.navigation.studyRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Route encoding calls [android.net.Uri.encode], so these run under Robolectric
 * (CI) exactly like [RoutesTest]. The scope model itself is pure-JVM tested in
 * [StudyScopeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudyScopeRouteTest {

    @Test
    fun routeKeepsLegacyShapeAndAddsScopeAtTheEnd() {
        assertEquals("study/p1", studyRoute("p1"))
        assertEquals("study/p1?leechesOnly=true", studyRoute("p1", leechesOnly = true))
        val routed = studyRoute("p1", tag = "Hindi", mistakes = true)
        assertTrue(routed.contains("tag=Hindi"))
        assertTrue(routed.contains("mistakes=true"))
    }

    @Test
    fun scopeArgumentsRoundTripWithCategories() {
        val route = studyRoute("p1", categoryIds = listOf("c1", "c2"), tag = "Physics")
        val categories = decodeCategoryList(
            route.substringAfter("categories=", "").substringBefore("&")
        )
        assertEquals(listOf("c1", "c2"), categories)
    }

    @Test
    fun switchingFromADrillBuildsAFreshTagRouteWithoutTheDrillFlag() {
        val selection = studyScopeMenuSelection(
            currentCategoryIds = listOf("c1"),
            choice = StudyScopeMenuChoice.TAG,
            foldedTag = "Physics"
        )
        val route = studyRoute(
            "p1", selection.categoryIds, selection.leechesOnly, selection.tag, selection.mistakes
        )
        assertFalse(route, route.contains("leechesOnly"))
        assertTrue(route, route.contains("tag=Physics"))
        assertTrue(route, route.contains("categories="))
    }

    @Test
    fun switchingToWholeFromACategoryScopeBuildsThePlainRoute() {
        val selection = studyScopeMenuSelection(
            currentCategoryIds = listOf("c1", "c2"),
            choice = StudyScopeMenuChoice.WHOLE
        )
        assertEquals(
            "study/p1",
            studyRoute(
                "p1", selection.categoryIds, selection.leechesOnly, selection.tag, selection.mistakes
            )
        )
    }
}
