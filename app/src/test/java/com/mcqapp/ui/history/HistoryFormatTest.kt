package com.mcqapp.ui.history

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** History rows keep ASCII digits even when the device locale does not. */
class HistoryFormatTest {

    @Test
    fun percentUsesAsciiDigitsAndADotDecimal() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("91%", formatPercent(91.2))
            assertEquals("100%", formatPercent(100.0))
            assertEquals("0%", formatPercent(0.0))
        } finally {
            Locale.setDefault(original)
        }
    }
}
