package com.mcqapp

import com.mcqapp.domain.Marks
import org.junit.Assert.assertEquals
import org.junit.Test

class MarksTest {

    @Test
    fun parseAcceptsValidWeights() {
        assertEquals(2.0, Marks.parse("2"), 0.0001)
        assertEquals(2.5, Marks.parse(" 2.5 "), 0.0001)
        assertEquals(0.0, Marks.parse("0"), 0.0001)
    }

    @Test
    fun parseFallsBackToOne() {
        assertEquals(1.0, Marks.parse(""), 0.0001)
        assertEquals(1.0, Marks.parse("lots"), 0.0001)
        assertEquals(1.0, Marks.parse("-2"), 0.0001)
        assertEquals(1.0, Marks.parse("NaN"), 0.0001)
        assertEquals(1.0, Marks.parse("Infinity"), 0.0001)
    }

    @Test
    fun formatDropsRedundantDecimals() {
        assertEquals("2", Marks.format(2.0))
        assertEquals("2.5", Marks.format(2.5))
        assertEquals("0", Marks.format(0.0))
    }
}
