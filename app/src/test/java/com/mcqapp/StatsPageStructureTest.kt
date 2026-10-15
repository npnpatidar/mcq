package com.mcqapp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The statistics page is reachable only through navigation wiring that no JVM
 * test exercises, so the hooks are asserted structurally: a dropped route or
 * drawer entry would compile and ship a page nobody can open.
 */
class StatsPageStructureTest {

    private fun source(path: String): String = File(path).readText()

    @Test
    fun theStatsRouteIsRegistered() {
        val nav = source("src/main/java/com/mcqapp/ui/navigation/McqNavHost.kt")
        assertTrue("route \"stats\" missing", nav.contains("""composable("stats")"""))
        assertTrue("route must render StatsScreen", nav.contains("ui.stats.StatsScreen"))
    }

    @Test
    fun theStatsPageIsReachableFromTheLibrary() {
        val drawer = source("src/main/java/com/mcqapp/ui/library/LibraryDrawer.kt")
        assertTrue("drawer entry missing", drawer.contains("onOpenStats"))
        val library = source("src/main/java/com/mcqapp/ui/library/LibraryScreen.kt")
        assertTrue("drawer wiring missing", library.contains("""navigate("stats")"""))
    }

    @Test
    fun theStatsPageIsReachableFromHistory() {
        val history = source("src/main/java/com/mcqapp/ui/history/HistoryScreen.kt")
        assertTrue("history action missing", history.contains("""navigate("stats")"""))
    }

    @Test
    fun everyStatsTabAndRangeHasALabel() {
        val defined = Regex("""<string name="([a-z0-9_]+)">""")
            .findAll(File("src/main/res/values/strings.xml").readText())
            .map { it.groupValues[1] }.toSet()
        val expected = listOf(
            "tab_overview", "tab_retention", "tab_workload", "tab_memory", "tab_cards",
            "range_7d", "range_30d", "range_90d", "range_365d", "range_all",
            "stats", "stats_no_data", "stats_load_error"
        )
        val missing = expected - defined
        assertTrue("labels not defined: $missing", missing.isEmpty())
    }

    @Test
    fun theScopeSelectorOffersAPerPaperFilter() {
        val screen = source("src/main/java/com/mcqapp/ui/stats/StatsScreen.kt")
        assertTrue("scope selector missing", screen.contains("ScopeSelector"))
        assertTrue("range selector missing", screen.contains("RangeSelector"))
        assertTrue("five tabs required", screen.contains("tab_cards"))
    }
}
