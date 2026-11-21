package com.mcqapp

import com.mcqapp.data.export.answerKeyEntry
import com.mcqapp.data.export.optionLetter
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which questions the PDF answer key lists, and what their line says.
 *
 * The produced PDF bytes cannot be asserted here: Robolectric's PdfDocument is
 * closed at construction, so the PDF byte checks live in
 * `PdfPaperWriterDeviceTest` on the emulator. These pin the rules behind an
 * answer-key line instead, and `PdfOversizedBlockTest`
 * covers the page flow through an injected page sink.
 */
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
    fun multipleCorrectOptionsAreCommaSeparatedInPrintOrder() {
        val three = options + OptionDto(id = "x3", text = "three")
        // Selected out of order on purpose: the line follows the printed
        // positions, not the order the ids happen to be stored in.
        val question = QuestionDto(
            id = "q1",
            options = three,
            correctOptionIds = listOf("x3", "x1")
        )
        assertEquals("Q1. A, C", answerKeyEntry(question, 1))
    }

    @Test
    fun labelsRunPastZLikeASpreadsheet() {
        assertEquals("A", optionLetter(0))
        assertEquals("Z", optionLetter(25))
        assertEquals("AA", optionLetter(26))
        assertEquals("AB", optionLetter(27))
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

    /**
     * A zero-option question is a flashcard: its back is the answer, so the
     * key prints it instead of the `—` placeholder that stood where a card's
     * answer belongs.
     */
    @Test
    fun aCardPrintsItsAnswerInsteadOfThePlaceholder() {
        val card = QuestionDto(
            id = "q1",
            options = emptyList(),
            explanationElements = listOf(ContentElement.TextElement("Paris, the capital."))
        )
        assertEquals("Q7. Paris, the capital.", answerKeyEntry(card, 7))
    }

    /** A card whose back is only the legacy text field is still a card. */
    @Test
    fun aLegacyTextCardAlsoPrintsItsAnswer() {
        val card = QuestionDto(id = "q1", options = emptyList(), explanation = "Paris.")
        assertEquals("Q7. Paris.", answerKeyEntry(card, 7))
    }

    /**
     * A picture cannot ride the entry line: the line carries the explicit
     * `(card)` marker and the picture prints under it (the key renders the
     * back's images), so the card is never dropped or shown as `—`.
     */
    @Test
    fun aPictureOnlyCardKeepsAnExplicitMarker() {
        val card = QuestionDto(id = "q1", options = emptyList(), explanationImage = "mcq-0.png")
        assertEquals("Q7. (card)", answerKeyEntry(card, 7))
    }

    /** A card with no back at all has nothing to key. */
    @Test
    fun aCardWithNoBackIsNotListed() {
        assertNull(answerKeyEntry(QuestionDto(id = "q1", options = emptyList()), 3))
    }
}
