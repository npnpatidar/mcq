package com.mcqapp

import com.mcqapp.ui.test.questionProgressLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionProgressLabelTest {

    @Test
    fun singleMarkKeepsItsDwellSuffix() {
        // Regression: `marks == 1.0` is the default for every question, and
        // an inline `if` in a `+` chain used to swallow everything after it.
        assertEquals(
            "Question 3 of 10 · 1 mark · 0:42 here",
            questionProgressLabel(index = 2, total = 10, marks = 1.0, dwellSeconds = 42L)
        )
    }

    @Test
    fun zeroDwellStillRenders() {
        assertEquals(
            "Question 1 of 3 · 1 mark · 0:00 here",
            questionProgressLabel(index = 0, total = 3, marks = 1.0, dwellSeconds = 0L)
        )
    }

    @Test
    fun multipleMarksUseThePluralAndKeepDwell() {
        assertEquals(
            "Question 1 of 3 · 2 marks · 1:05 here",
            questionProgressLabel(index = 0, total = 3, marks = 2.0, dwellSeconds = 65L)
        )
    }

    @Test
    fun fractionalMarksAreNotCollapsedToIntegers() {
        assertEquals(
            "Question 2 of 5 · 1.5 marks · 0:07 here",
            questionProgressLabel(index = 1, total = 5, marks = 1.5, dwellSeconds = 7L)
        )
    }
}