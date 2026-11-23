package com.mcqapp.ui.stats.sections

import com.mcqapp.R
import com.mcqapp.domain.stats.Calibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Pure-JVM regressions for the calibration evidence-window disclosure. The
 * decision and the bound formatter are plain functions, so these run on the
 * ARM64 host without Robolectric or a Compose test rule.
 */
class CalibrationDisclosureTest {

    private fun report(
        pairs: Int = 1,
        firstExposureCount: Int = 0,
        epochStart: Long = 1_753_000_000_000L,
        epochDays: Int = 30,
        refit: Boolean = false
    ): Calibration.Report {
        val contrast = Calibration.Contrast(
            value = null,
            low = null,
            high = null,
            method = Calibration.IntervalMethod.NONE,
            bindingCards = 0
        )
        return Calibration.Report(
            pairs = pairs,
            excluded = 0,
            exclusions = emptyList(),
            grades = emptyList(),
            contrast1 = contrast,
            contrast2 = contrast,
            followUp = emptyList(),
            followUpGatePassed = true,
            followUpGap = null,
            firstExposure = Calibration.GradeClass.entries.map { grade ->
                Calibration.FirstExposure(
                    grade = grade,
                    count = if (grade == Calibration.GradeClass.GOOD) firstExposureCount else 0,
                    correct = 0
                )
            },
            blocks = emptyList(),
            epochStart = epochStart,
            epochDays = epochDays,
            readiness = Calibration.Readiness(
                pairs = pairs,
                bindingCards = 0,
                halfWidth = null,
                calendarDays = epochDays,
                refitInEpoch = refit
            ),
            hardUnderpowered = false
        )
    }

    @Test
    fun emptyCalibrationExplainsTimedGradingOnlyUnderThatRule() {
        assertEquals(
            R.string.stats_calibration_empty_timed,
            calibrationEmptyRes(samples = 0, timeBasedGrading = true)
        )
        assertEquals(
            "correctness-only keeps the existing line",
            R.string.stats_calibration_empty,
            calibrationEmptyRes(samples = 0, timeBasedGrading = false)
        )
        assertEquals(
            "a non-empty sample never shows an empty line",
            R.string.stats_calibration_empty,
            calibrationEmptyRes(samples = 3, timeBasedGrading = true)
        )
        val english = File("src/main/res/values/strings.xml").readText()
        val hindi = File("src/main/res/values-hi/strings_hi_5.xml").readText()
        for (file in listOf(english, hindi)) {
            assertTrue(
                "the timed line must exist in every locale",
                file.contains("name=\"stats_calibration_empty_timed\"")
            )
        }
        assertTrue(
            "the timed line must point at the switch that fixes the gate",
            english.contains("Grade on correctness only") &&
                hindi.contains("केवल सही-गलत के आधार पर ग्रेड करें")
        )
        val src = File("src/main/java/com/mcqapp/ui/stats/sections/RetentionSection.kt").readText()
        assertTrue(
            src,
            src.contains("calibrationEmptyRes(calibration.samples, bundle.timeBasedGrading)")
        )
    }

    @Test
    fun captionHiddenForFreshUserWithNoEvidence() {
        val caption = evidenceWindowCaption(report(pairs = 0, firstExposureCount = 0))
        assertFalse(caption.show)
        assertFalse(caption.refit)
    }

    @Test
    fun captionShownWithoutRefitLineWhenNoRefit() {
        val caption = evidenceWindowCaption(report(refit = false))
        assertTrue(caption.show)
        assertFalse("no refit means no refit line", caption.refit)
    }

    @Test
    fun captionIncludesNoDateRefitCopyWhenRefitInEpoch() {
        val withRefit = report(refit = true)
        val caption = evidenceWindowCaption(withRefit)
        assertTrue(caption.show)
        assertTrue("a refit must add the second caption line", caption.refit)

        // The began line still carries the real formatted date; the refit line
        // carries no date at all, because no refit timestamp is stored.
        val strings = File("src/main/res/values/strings_disclosure_calibration.xml").readText()
        assertTrue(strings.contains("name=\"stats_calibration_window_began\">Evidence window began %1\$s"))
        val refit = Regex("""<string name="stats_calibration_window_refit">(.*?)</string>""")
            .find(strings)?.groupValues?.get(1)
        assertNotNull("refit string must exist", refit)
        assertTrue("refit copy must be date-free: $refit", refit!!.contains("not yet reliable"))
        assertFalse("refit copy must have no format arg: $refit", refit.contains("%"))
        val beganText = formatEvidenceDate(withRefit.epochStart, Locale.US)
        val expected = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.US)
            .format(java.util.Date(withRefit.epochStart))
        assertEquals("began line must show a locale-formatted date", expected, beganText)
        assertFalse("raw epoch millis must not reach the UI", beganText.contains(withRefit.epochStart.toString()))
    }

    @Test
    fun captionHiddenWhenEpochStartIsMissing() {
        assertFalse(evidenceWindowCaption(report(epochStart = 0L)).show)
    }

    @Test
    fun captionHiddenWhenWindowHasNoDays() {
        assertFalse(evidenceWindowCaption(report(epochDays = 0)).show)
    }

    @Test
    fun missingBoundsRenderDashNotFabricatedZero() {
        assertEquals("—", formatBound(null))
    }

    @Test
    fun presentBoundsKeepTheirSignedPercentagePoints() {
        assertEquals("+0.0 pp", formatBound(0.0))
        assertEquals("-1.5 pp", formatBound(-0.015))
        assertEquals("+2.0 pp", formatBound(0.02))
    }

    @Test
    fun evidenceDateUsesLocaleNotRawMillis() {
        val ms = 1_700_000_000_000L
        val text = formatEvidenceDate(ms, Locale.US)
        assertFalse("raw epoch millis must not reach the UI", text.contains(ms.toString()))
        val expected = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.US)
            .format(java.util.Date(ms))
        assertEquals("expected a localised calendar date", expected, text)
    }

    @Test
    fun sourceUsesDashAndCaptionResources() {
        val src = File("src/main/java/com/mcqapp/ui/stats/sections/RetentionSection.kt").readText()
        assertTrue(src.contains("formatBound(contrast.low)"))
        assertTrue(src.contains("formatBound(contrast.high)"))
        assertTrue(src.contains("R.string.stats_calibration_window_began"))
        assertTrue(src.contains("stringResource(R.string.stats_calibration_window_began, date)"))
        assertTrue("refit line must pass no date", src.contains("stringResource(R.string.stats_calibration_window_refit)"))
        assertTrue(src.contains("R.string.stats_calibration_contrast_disclosed"))
    }
}
