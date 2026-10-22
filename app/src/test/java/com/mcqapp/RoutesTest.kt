package com.mcqapp

import android.net.Uri
import com.mcqapp.ui.navigation.browseRoute
import com.mcqapp.ui.navigation.editorRoute
import com.mcqapp.ui.navigation.resultsRoute
import com.mcqapp.ui.navigation.studyRoute
import com.mcqapp.ui.navigation.testRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ids reach the navigation layer straight from imported files, so they are not
 * trusted to be free of `&`, `#`, `/` or `?`. An unencoded id used to reshape
 * the destination route.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutesTest {

    private val hostile = "x&paperId=other-paper"
    private val slashy = "a/b?c#d"

    @Test
    fun anEditorRouteCannotBeHijackedByAnId() {
        val route = editorRoute(hostile, "p1", "c1")
        // Exactly the three declared parameters, in order.
        assertEquals(
            "editor?questionId=x%26paperId%3Dother-paper&paperId=p1&categoryId=c1",
            route
        )
        assertEquals(
            3,
            route.split("&").count { it.contains('=') }
        )
    }

    @Test
    fun anEditorRouteEncodesEverySegment() {
        assertEquals(
            "editor?questionId=a%2Fb%3Fc%23d&paperId=&categoryId=",
            editorRoute(slashy, "", "")
        )
    }

    @Test
    fun ordinaryIdsAreUnchanged() {
        assertEquals("paper-1", Uri.decode(testRoute("paper-1").substringAfter("paperId=").substringBefore("&")))
        assertEquals("study/paper-1", studyRoute("paper-1"))
        assertEquals("browse/paper-1", browseRoute("paper-1"))
        assertEquals("browse/paper-1?focus=q1", browseRoute("paper-1", "q1"))
        assertEquals("results/42", resultsRoute(42L))
    }

    @Test
    fun aPathSegmentWithASlashStaysOneSegment() {
        val route = studyRoute(slashy)
        assertEquals("study/a%2Fb%3Fc%23d", route)
        assertEquals(1, route.removePrefix("study/").count { it == '/' } + 1)
    }

    @Test
    fun theCategoryListSurvivesCommas() {
        val route = testRoute("p1", listOf("c1", "c2"))
        assertEquals("c1,c2", Uri.decode(route.substringAfter("categories=").substringBefore("&")))
        // Commas must be encoded or the CSV would be ambiguous in the URL.
        assertEquals("c1%2Cc2", route.substringAfter("categories=").substringBefore("&"))
    }

    @Test
    fun aCategoryIdCannotSmuggleAnotherParameter() {
        val route = testRoute("p1", listOf("c1&mistakes=true"))
        assertEquals(1, route.split("&").count { it.startsWith("categories=") })
        assertEquals("false", route.substringAfter("&mistakes=").substringBefore("&"))
    }

    @Test
    fun theBrowseRouteCarriesAScopedFilterWithoutChangingTheOldShapes() {
        // Pre-scope callers keep their URL byte-for-byte.
        assertEquals("browse/paper-1", browseRoute("paper-1"))
        assertEquals("browse/paper-1?focus=q1", browseRoute("paper-1", "q1"))

        val route = browseRoute("paper-1", categoryIds = listOf("c1", "c2"))
        assertEquals("browse/paper-1?categories=c1%2Cc2", route)
        assertEquals(
            listOf("c1", "c2"),
            Uri.decode(route.substringAfter("categories=")).split(',')
        )
        assertEquals(
            "browse/paper-1?focus=q1&categories=c1",
            browseRoute("paper-1", focusQuestionId = "q1", categoryIds = listOf("c1"))
        )
    }

    @Test
    fun drillParametersAreCarried() {
        val route = testRoute("p1", drillCount = 10, drillMinutes = 5)
        assertEquals("10", route.substringAfter("drillCount=").substringBefore("&"))
        assertEquals("5", route.substringAfter("drillMinutes="))
    }

    // --- scoped study routes (§9.5) ---

    @Test
    fun aWholePaperStudyRouteIsUnchanged() {
        // No query at all: the old route must stay byte-for-byte.
        assertEquals("study/paper-1", studyRoute("paper-1"))
        assertEquals("study/paper-1?leechesOnly=true", studyRoute("paper-1", leechesOnly = true))
    }

    @Test
    fun studyCarriesCategories() {
        val route = studyRoute("p1", listOf("c1", "c2"))
        assertEquals("study/p1?categories=c1%2Cc2", route)
        // Commas are encoded, so the CSV is unambiguous in the URL.
        assertEquals("c1,c2", Uri.decode(route.substringAfter("categories=")))
    }

    @Test
    fun studyCarriesLeeches() {
        val route = studyRoute("p1", leechesOnly = true)
        assertEquals("study/p1?leechesOnly=true", route)
        assertFalse(route.contains("categories="))
        // Both, in the declared order.
        assertEquals(
            "study/p1?categories=c1&leechesOnly=true",
            studyRoute("p1", listOf("c1"), leechesOnly = true)
        )
    }

    @Test
    fun aScopedStudyRouteEncodesThePaperSegment() {
        val route = studyRoute(slashy, listOf("c1"))
        assertEquals("study/a%2Fb%3Fc%23d?categories=c1", route)
        // The scope must not reshape the paper segment into a query parameter.
        assertEquals(1, route.removePrefix("study/").substringBefore("?").count { it == '/' } + 1)
    }
}
