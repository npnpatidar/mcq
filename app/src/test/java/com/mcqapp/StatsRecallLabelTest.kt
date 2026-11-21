package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Overview "today" tile's label. Its percentage is the learner's own
 * grade — the share of reviews not answered Again — so it must read "Recall",
 * never "Correct": a self-graded card review has no objective outcome, and the
 * tile must not imply one. Structural, like the other copy guards: the tile is
 * checked where it is written and the resources are checked in both locales.
 */
class StatsRecallLabelTest {

    private fun overviewSource(): String =
        File("src/main/java/com/mcqapp/ui/stats/sections/OverviewSection.kt").readText()

    /** The `TodayCard` composable body, up to the next declaration. */
    private fun todayTile(source: String): String {
        val start = source.indexOf("private fun TodayCard(")
        assertTrue("OverviewSection no longer declares TodayCard", start >= 0)
        val end = source.indexOf("\n@Composable", start)
        assertTrue("TodayCard has no following declaration to bound it", end > start)
        return source.substring(start, end)
    }

    /** `<string name="...">` values declared under one `res/values*` directory. */
    private fun declaredStrings(directory: String): Map<String, String> =
        File(directory).walkTopDown()
            .filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }
            .flatMap { file ->
                Regex(
                    """<string name="([a-z0-9_]+)"[^>]*>(.*?)</string>""",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }
            }
            .toMap()

    @Test
    fun theTodayTileResolvesRecallAndNotCorrect() {
        val tile = todayTile(overviewSource())
        assertTrue(
            "the today tile must resolve R.string.stats_recall",
            "R.string.stats_recall" in tile
        )
        assertFalse(
            "the today tile must not claim objective correctness",
            "R.string.stats_correct" in tile
        )
    }

    @Test
    fun theRecallLabelExistsInBothLocalesAndStaysTranslated() {
        val english = declaredStrings("src/main/res/values")
        val hindi = declaredStrings("src/main/res/values-hi")
        assertTrue("stats_recall must be declared in English", "stats_recall" in english)
        assertTrue("stats_recall must be declared in Hindi", "stats_recall" in hindi)
        assertEquals("Recall", english.getValue("stats_recall"))
        assertTrue(
            "the Hindi label must not be left blank",
            hindi.getValue("stats_recall").isNotBlank()
        )
        assertNotEquals(
            "the Hindi label must be translated",
            english.getValue("stats_recall"),
            hindi.getValue("stats_recall")
        )
        assertFalse(
            "the old objective-sounding key must not survive the rename",
            "stats_correct" in english || "stats_correct" in hindi
        )
    }
}
