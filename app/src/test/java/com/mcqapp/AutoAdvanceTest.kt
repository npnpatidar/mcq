package com.mcqapp

import com.mcqapp.domain.AutoAdvance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoAdvanceTest {

    @Test
    fun advancesForSingleCorrectMidTest() {
        assertEquals(4, AutoAdvance.nextIndex(true, false, 3, 10))
    }

    @Test
    fun staysWhenDisabledMultiOrLast() {
        assertNull(AutoAdvance.nextIndex(false, false, 3, 10))
        assertNull(AutoAdvance.nextIndex(true, true, 3, 10))
        assertNull(AutoAdvance.nextIndex(true, false, 9, 10))
    }
}
