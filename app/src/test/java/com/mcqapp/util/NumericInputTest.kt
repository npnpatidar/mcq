package com.mcqapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One shared numeric-entry pipeline for three screens; a comma-locale
 * keyboard's decimal key inserts ',', which the per-screen digit filters
 * used to delete — "0,25" became "025" (25 marks per wrong answer!) and the
 * import screen's value-clause made 0.5 un-typeable.
 */
class NumericInputTest {

    @Test
    fun aCommaDecimalEntryFoldsIntoADot() {
        assertEquals("0.25", filterDecimalEntry("0,25"))
        assertEquals(0.25, parseDecimalEntry("0,25"), 0.0)
        assertEquals("1.5", filterDecimalEntry("1,5"))
        assertEquals(1.5, parseDecimalEntry("1,5"), 0.0)
    }

    @Test
    fun thousandsSeparatorJunkIsRejectedNotMisread() {
        // Several separators: fold-then-parse cannot turn "1,23,456" into a
        // number, so the 0.0 fallback applies — the same rejection the dot
        // junk ("1.23.4") always had.
        assertEquals(0.0, parseDecimalEntry("1,23,456"), 0.0)
        assertEquals(0.0, parseDecimalEntry("1.23.4"), 0.0)
        assertEquals(0.0, parseDecimalEntry(""), 0.0)
    }

    @Test
    fun theLeadingMinusIsKeptOnlyWhereTheRangeAllowsIt() {
        assertEquals("-1.5", filterDecimalEntry("-1,5", allowLeadingMinus = true))
        // A minus typed mid-text is not a sign: the settings row's rule (a
        // leading minus only) is preserved by the shared helper, so it drops
        // — "11.5", not "1-1.5".
        assertEquals("11.5", filterDecimalEntry("1-1,5", allowLeadingMinus = true))
        // Where the field's range never goes below zero the minus is dropped.
        assertEquals("1.5", filterDecimalEntry("-1,5"))
    }

    @Test
    fun plainDotEntriesKeepWorking() {
        assertEquals("0.25", filterDecimalEntry("0.25"))
        assertEquals(0.25, parseDecimalEntry("0.25"), 0.0)
        assertEquals(0.0, parseDecimalEntry("nope"), 0.0)
    }

    @Test
    fun aValueLearnedFromAFileRendersItsTextNotTheBlankClause() {
        assertEquals("0.33", com.mcqapp.ui.importscreen.numericTextFor(0.33))
        assertEquals("", com.mcqapp.ui.importscreen.numericTextFor(0.0))
        assertEquals("10", com.mcqapp.ui.importscreen.numericTextFor(10))
    }
}
