package com.mcqapp

import com.mcqapp.ui.settings.formatValue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The scheduler rows re-parse the text [formatValue] renders, so the
 * rendering must not be default-locale: a comma-locale JVM ("10,5") would
 * fail its own validity parse on edit. With a de-DE default forced here, a
 * default-locale String.format renders "10,5" and turns this test red — the
 * Locale.ROOT fix is what keeps it green under the same conditions.
 */
class SettingsFormatValueTest {

    @Test
    fun decimalStepsRenderLocaleIndependently() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("de", "DE"))
            // step < 1: the FractionDigits path — the one that used the
            // default locale's decimal comma.
            assertEquals("10.5", formatValue(10.5, 0.1))
            assertEquals("0.25", formatValue(0.25, 0.01))
            // Whole steps skip String.format entirely.
            assertEquals("2", formatValue(2.0, 1.0))
        } finally {
            Locale.setDefault(original)
        }
    }
}
