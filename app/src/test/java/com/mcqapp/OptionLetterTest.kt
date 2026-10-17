package com.mcqapp

import com.mcqapp.data.export.answerLabels
import com.mcqapp.data.export.optionLetter
import com.mcqapp.data.io.OptionDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** The labels HTML, PDF and Anki exports name their options and answers with. */
class OptionLetterTest {

    @Test
    fun labelsRunPastZLikeASpreadsheet() {
        assertEquals("A", optionLetter(0))
        assertEquals("B", optionLetter(1))
        assertEquals("Z", optionLetter(25))
        assertEquals("AA", optionLetter(26))
        assertEquals("AB", optionLetter(27))
        assertEquals("BA", optionLetter(52))
    }

    @Test
    fun answerNamesTheCorrectOptionsInListOrder() {
        val options = listOf(
            OptionDto(id = "x1", text = "one"),
            OptionDto(id = "x2", text = "two"),
            OptionDto(id = "x3", text = "three")
        )
        assertEquals(listOf("B"), answerLabels(options, listOf("x2")))
        // Stored order must not leak into the printed answer.
        assertEquals(listOf("A", "C"), answerLabels(options, listOf("x3", "x1")))
        assertEquals(emptyList<String>(), answerLabels(options, listOf("gone")))
    }
}
