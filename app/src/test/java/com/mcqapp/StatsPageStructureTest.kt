package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

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
    fun theSessionsHistoryRouteIsRegistered() {
        val nav = source("src/main/java/com/mcqapp/ui/navigation/McqNavHost.kt")
        assertTrue("route \"sessions\" missing", nav.contains("""composable("sessions")"""))
        assertTrue("route must render SessionsScreen", nav.contains("ui.sessions.SessionsScreen"))
        val drawer = source("src/main/java/com/mcqapp/ui/library/LibraryDrawer.kt")
        assertTrue("drawer entry missing", drawer.contains("onOpenSessions"))
        val library = source("src/main/java/com/mcqapp/ui/library/LibraryScreen.kt")
        assertTrue("drawer wiring missing", library.contains("""navigate("sessions")"""))
    }

    @Test
    fun everyStatsTabAndRangeHasALabel() {
        val defined = Regex("""<string name="([a-z0-9_]+)">""")
            .findAll(File("src/main/res/values/strings.xml").readText())
            .map { it.groupValues[1] }.toSet()
        val expected = listOf(
            "tab_overview", "tab_retention", "tab_workload", "tab_sessions", "tab_memory",
            "tab_cards",
            "range_7d", "range_30d", "range_90d", "range_365d", "range_all",
            "stats", "stats_no_data", "stats_load_error"
        )
        val missing = expected - defined
        assertTrue("labels not defined: $missing", missing.isEmpty())
    }

    @Test
    fun theGuessingCoverageNoteIsOnlyShownWhenRowsWereLeftOut() {
        val guessing = source("src/main/java/com/mcqapp/ui/stats/sections/GuessingSection.kt")
        assertTrue(
            "the coverage note must be guarded by the left-out count",
            guessing.contains("bundle.unflaggedReviews > 0") &&
                guessing.contains("pluralStringResource(") &&
                guessing.contains("R.plurals.stats_guess_coverage_note")
        )
    }

    /**
     * Android resolves the note from the left-out count, so it must be a plural
     * with a real singular and the same placeholder in every form and both
     * locales. Formatting is plain JVM string formatting — exactly what the
     * platform does once it has chosen the item.
     */
    @Test
    fun theGuessingCoverageNoteIsAPluralThatReadsForOneAndMany() {
        fun items(directory: String): Map<String, String> {
            val xml = File(directory).walkTopDown()
                .filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }
                .joinToString("\n") { it.readText() }
            val block = Regex(
                """<plurals name="stats_guess_coverage_note">(.*?)</plurals>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(xml)?.groupValues?.get(1)
            assertTrue("stats_guess_coverage_note plural missing in $directory", block != null)
            return Regex("""<item quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(block!!).associate { it.groupValues[1] to it.groupValues[2] }
        }

        val english = items("src/main/res/values")
        val hindi = items("src/main/res/values-hi")
        assertEquals(setOf("one", "other"), english.keys)
        assertEquals(setOf("one", "other"), hindi.keys)
        // A dropped placeholder would shift the count.
        val placeholders = (english.values + hindi.values).map { value ->
            Regex("""%\d+\$[a-zA-Z]""").findAll(value).map { it.value }.toSet()
        }.toSet()
        assertEquals(setOf(setOf("%1\$d")), placeholders)
        // A truthful singular, not a copy of the plural form.
        assertNotEquals(english.getValue("one"), english.getValue("other"))

        val one = String.format(Locale.ENGLISH, english.getValue("one"), 1)
        val many = String.format(Locale.ENGLISH, english.getValue("other"), 2)
        assertTrue(one, one.startsWith("1 in-range review has no guess flag"))
        assertTrue(one, one.endsWith("is left out of these figures."))
        assertTrue(many, many.startsWith("2 in-range reviews have no guess flag"))
        assertTrue(many, many.endsWith("are left out of these figures."))
    }

    @Test
    fun theScopeSelectorOffersAPerPaperFilter() {
        val screen = source("src/main/java/com/mcqapp/ui/stats/StatsScreen.kt")
        assertTrue("scope selector missing", screen.contains("ScopeSelector"))
        assertTrue("range selector missing", screen.contains("RangeSelector"))
        assertTrue("five tabs required", screen.contains("tab_cards"))
    }
}
