package com.mcqapp

import com.mcqapp.domain.TimerWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T4/T5: warning copy is localised in both locales with matching `%1$d`
 * placeholders on every plural quantity, the pure threshold mapping and count
 * contract stay JVM-testable, and the English fallback pinned by TestTimingTest
 * is left intact.
 */
class Round2TestSessionWarningsTest {

    @Test
    fun thresholdsMapToLocalizableKinds() {
        assertEquals(TimerWarnings.TimerWarningKind.FIVE_MINUTES, TimerWarnings.warningKind(300))
        assertEquals(TimerWarnings.TimerWarningKind.ONE_MINUTE, TimerWarnings.warningKind(60))
        assertEquals(TimerWarnings.TimerWarningKind.SECONDS, TimerWarnings.warningKind(42))
        assertEquals(TimerWarnings.TimerWarningKind.SECONDS, TimerWarnings.warningKind(301))
    }

    @Test
    fun warningCountsArePassedAsBothQuantityAndArgument() {
        // The VM resolves every warning with getQuantityString(resId, count, count).
        assertEquals(5, TimerWarnings.warningCount(300))
        assertEquals(1, TimerWarnings.warningCount(60))
        assertEquals(42, TimerWarnings.warningCount(42))
        assertEquals(301, TimerWarnings.warningCount(301))
    }

    @Test
    fun thePureEnglishFallbackIsUnchanged() {
        // The localised path must not weaken the legacy pure seam.
        assertEquals("5 minutes left", TimerWarnings.message(300))
        assertEquals("1 minute left — the test auto-submits at zero", TimerWarnings.message(60))
        assertEquals("42 seconds left", TimerWarnings.message(42))
    }

    @Test
    fun warningCopyHasMatchingPlaceholdersInBothLocales() {
        val en = File("src/main/res/values/strings_round2_test.xml").readText()
        val hi = File("src/main/res/values-hi/strings_round2_test_hi.xml").readText()
        val placeholder = "%1\$d"
        for (name in listOf(
            "r2_test_warning_minutes",
            "r2_test_warning_one_minute",
            "r2_test_warning_seconds"
        )) {
            val enItems = pluralItems(en, name)
            val hiItems = pluralItems(hi, name)
            assertEquals("$name English must have one and other", setOf("one", "other"), enItems.keys)
            assertEquals("$name Hindi must have one and other", setOf("one", "other"), hiItems.keys)
            for (quantity in listOf("one", "other")) {
                val enValue = enItems.getValue(quantity)
                val hiValue = hiItems.getValue(quantity)
                assertTrue("$name/$quantity missing in English", enValue.isNotEmpty())
                assertTrue("$name/$quantity missing in Hindi", hiValue.isNotEmpty())
                assertEquals("$name/$quantity English placeholder count", 1, occurrences(enValue, placeholder))
                assertEquals("$name/$quantity Hindi placeholder count", 1, occurrences(hiValue, placeholder))
                assertTrue("$name/$quantity must actually be translated", enValue != hiValue)
            }
        }
    }

    @Test
    fun mergedTimerNodeBindsMinutesAndSecondsInBothLocales() {
        val en = valueOf(File("src/main/res/values/strings_round2_test.xml").readText(), "r2_test_time_remaining")
        val hi = valueOf(File("src/main/res/values-hi/strings_round2_test_hi.xml").readText(), "r2_test_time_remaining")
        for ((locale, value) in listOf("en" to en, "hi" to hi)) {
            assertTrue("$locale timer string missing", value.isNotEmpty())
            assertTrue("$locale must bind minutes as arg 1", "%1\$02d" in value)
            assertTrue("$locale must bind seconds as arg 2", "%2\$02d" in value)
        }
        assertTrue("the timer node must be translated", en != hi)
    }

    private fun pluralItems(xml: String, name: String): Map<String, String> {
        val block = Regex("""<plurals name="$name"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1).orEmpty()
        return Regex("""<item quantity="([a-z]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun valueOf(xml: String, name: String): String =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1).orEmpty()

    private fun occurrences(text: String, needle: String): Int =
        if (needle.isEmpty()) 0
        else text.windowed(needle.length).count { it == needle }
}
