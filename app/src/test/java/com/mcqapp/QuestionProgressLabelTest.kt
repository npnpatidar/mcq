package com.mcqapp

import com.mcqapp.ui.test.questionProgressLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionProgressLabelTest {

    /**
     * Regression: `marks == 1.0` is the default for every question, and an
     * inline `if` in a `+` chain used to swallow everything after it. The
     * pieces now arrive localized from resources; the glue is pinned so no
     * swallow can return.
     */
    @Test
    fun singleMarkKeepsItsDwellSuffix() {
        assertEquals(
            "Question 3 of 10 · 1 mark · 0:42 here",
            questionProgressLabel(
                question = "Question 3 of 10",
                marks = "1 mark",
                here = "0:42 here"
            )
        )
    }

    @Test
    fun zeroDwellStillRenders() {
        assertEquals(
            "Question 1 of 3 · 1 mark · 0:00 here",
            questionProgressLabel(
                question = "Question 1 of 3",
                marks = "1 mark",
                here = "0:00 here"
            )
        )
    }

    @Test
    fun multipleMarksUseThePluralAndKeepDwell() {
        assertEquals(
            "Question 1 of 3 · 2 marks · 1:05 here",
            questionProgressLabel(
                question = "Question 1 of 3",
                marks = "2 marks",
                here = "1:05 here"
            )
        )
    }

    @Test
    fun fractionalMarksAreNotCollapsedToIntegers() {
        assertEquals(
            "Question 2 of 5 · 1.5 marks · 0:07 here",
            questionProgressLabel(
                question = "Question 2 of 5",
                marks = "1.5 marks",
                here = "0:07 here"
            )
        )
    }
}
