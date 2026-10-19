package com.mcqapp

import com.mcqapp.data.export.answerKeyEntry
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which questions the PDF answer key lists, and what their line says. */
class PdfAnswerKeyTest {

    private val options = listOf(
        OptionDto(id = "x1", text = "one"),
        OptionDto(id = "x2", text = "two")
    )

    @Test
    fun anAnsweredQuestionPrintsItsCorrectOptions() {
        val question = QuestionDto(id = "q1", options = options, correctOptionIds = listOf("x2"))
        assertEquals("Q1. B", answerKeyEntry(question, 1))
    }

    @Test
    fun aLegacyExplanationAloneEarnsAKeyEntry() {
        // Regression: the key used to count only rich elements and the image,
        // so a question whose explanation is the plain legacy string printed
        // its explanation under the key but had no line to print it under.
        val question = QuestionDto(id = "q1", options = options, explanation = "Because.")
        assertEquals("Q1. —", answerKeyEntry(question, 1))
    }

    @Test
    fun richExplanationAloneEarnsAKeyEntry() {
        val question = QuestionDto(
            id = "q1",
            options = options,
            explanationElements = listOf(ContentElement.TextElement("Because."))
        )
        assertEquals("Q1. —", answerKeyEntry(question, 1))
    }

    @Test
    fun explanationImageAloneEarnsAKeyEntry() {
        val question = QuestionDto(id = "q1", options = options, explanationImage = "mcq-0.png")
        assertEquals("Q1. —", answerKeyEntry(question, 1))
    }

    @Test
    fun aQuestionWithNoAnswerAndNoExplanationIsNotListed() {
        assertNull(answerKeyEntry(QuestionDto(id = "q1", options = options), 3))
        assertNull(answerKeyEntry(QuestionDto(id = "q2"), 4))
    }
}
