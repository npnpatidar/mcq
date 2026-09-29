package com.mcqapp

import com.mcqapp.domain.ExamMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamModeTest {

    @Test
    fun normalModeAllowsEverything() {
        assertTrue(ExamMode.canReveal(false, false))
        assertTrue(ExamMode.canFlag(false))
        assertTrue(ExamMode.canOpenPalette(false))
        assertFalse(ExamMode.effectivePractice(false, false))
    }

    @Test
    fun practiceHidesOnlyReveal() {
        assertFalse(ExamMode.canReveal(true, false))
        assertTrue(ExamMode.canFlag(false))
        assertTrue(ExamMode.effectivePractice(true, false))
    }

    @Test
    fun strictHidesAllAidsAndSuppressesPractice() {
        assertFalse(ExamMode.canReveal(false, true))
        assertFalse(ExamMode.canFlag(true))
        assertFalse(ExamMode.canOpenPalette(true))
        assertFalse(ExamMode.effectivePractice(true, true))
    }
}
