package com.mcqapp

import com.mcqapp.data.io.FALLBACK_CLOZE_EXPLANATION
import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.countFlashcardConversions
import com.mcqapp.data.io.normalizedFlashcard
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.textContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The pure half of the one-option import rule: the DTO shape the importers
 * hash and write. The Robolectric importer tests cover the stored rows; this
 * pins the conversion itself without a database.
 */
class FlashcardNormalizationTest {

    private fun oneOption(
        explanation: List<ContentElement> = listOf(ContentElement.TextElement("It is the capital.")),
        explanationImage: String? = null,
        optionImage: String? = null
    ) = QuestionDto(
        id = "q1",
        text = "Capital of France?",
        options = listOf(OptionDto("a", "Paris", image = optionImage)),
        correctOptionIds = listOf("a"),
        explanation = explanation.textContent,
        explanationElements = explanation,
        explanationImage = explanationImage
    )

    @Test
    fun theSingleOptionBecomesTheFirstBackBlock() {
        val normalized = oneOption().normalizedFlashcard()

        assertEquals(emptyList<OptionDto>(), normalized.options)
        assertEquals(emptyList<String>(), normalized.correctOptionIds)
        assertEquals(
            listOf(
                ContentElement.TextElement("Paris"),
                ContentElement.TextElement("It is the capital.")
            ),
            normalized.explanationElements
        )
        assertEquals("ParisIt is the capital.", normalized.explanation)
    }

    @Test
    fun theClozePlaceholderIsReplacedByTheAnswer() {
        val normalized = oneOption(
            explanation = listOf(ContentElement.TextElement(FALLBACK_CLOZE_EXPLANATION))
        ).normalizedFlashcard()

        assertEquals(listOf(ContentElement.TextElement("Paris")), normalized.explanationElements)
    }

    @Test
    fun theAnswersPictureBecomesTheBacksScalarImage() {
        val normalized = oneOption(optionImage = "answer.png").normalizedFlashcard()

        assertEquals("answer.png", normalized.explanationImage)
        // An explanation that already has its own picture keeps it.
        assertEquals(
            "explanation.png",
            oneOption(explanationImage = "explanation.png", optionImage = "answer.png")
                .normalizedFlashcard().explanationImage
        )
    }

    @Test
    fun aZeroOptionFlashcardPassesThroughUnchanged() {
        val flashcard = QuestionDto(
            id = "q2",
            text = "Capital of Japan?",
            options = emptyList(),
            correctOptionIds = emptyList(),
            explanationElements = listOf(ContentElement.TextElement("Tokyo"))
        )
        assertSame(flashcard, flashcard.normalizedFlashcard())
    }

    @Test
    fun theHashFollowsTheNormalisedShape() {
        val raw = oneOption()
        val normalized = raw.normalizedFlashcard()

        assertNotEquals(ContentHash.of(raw), ContentHash.of(normalized))
        // What the importer hashes is the zero-option DTO: the same content a
        // re-import of the normalised file produces.
        assertEquals(ContentHash.of(normalized), ContentHash.of(normalized.copy(id = "other")))
    }

    @Test
    fun aGeneratedOneOptionIdIsReDerivedFromThePrompt() {
        val prompt = "Capital of France?"
        val parserId = LegacyParser.stableQuestionId(prompt, listOf(OptionDto("a", "Paris")))
        val normalized = QuestionDto(
            id = parserId,
            text = prompt,
            options = listOf(OptionDto("a", "Paris")),
            correctOptionIds = listOf("a")
        ).normalizedFlashcard()

        assertEquals(LegacyParser.stableQuestionId(prompt, emptyList()), normalized.id)
        assertNotEquals("the option-inclusive parser id must move", parserId, normalized.id)
    }

    @Test
    fun aBlankIdBecomesThePromptOnlyId() {
        val normalized = QuestionDto(
            id = "",
            text = "Capital of France?",
            options = listOf(OptionDto("a", "Paris")),
            correctOptionIds = listOf("a")
        ).normalizedFlashcard()

        assertEquals(
            LegacyParser.stableQuestionId("Capital of France?", emptyList()),
            normalized.id
        )
    }

    @Test
    fun anAuthoredIdSurvivesTheConversion() {
        val normalized = QuestionDto(
            id = "q1",
            text = "Capital of France?",
            options = listOf(OptionDto("a", "Paris")),
            correctOptionIds = listOf("a")
        ).normalizedFlashcard()

        assertEquals("q1", normalized.id)
    }

    @Test
    fun aTwoOptionIdIsNeverReMinted() {
        val parserId = LegacyParser.stableQuestionId(
            "2 + 2?",
            listOf(OptionDto("a", "3"), OptionDto("b", "4"))
        )
        val question = QuestionDto(
            id = parserId,
            text = "2 + 2?",
            options = listOf(OptionDto("a", "3"), OptionDto("b", "4")),
            correctOptionIds = listOf("b")
        )

        assertEquals(parserId, question.normalizedFlashcard().id)
        assertEquals(0, McqFileDto().countFlashcardConversions())
    }

    @Test
    fun theConversionCountFollowsTheOneOptionShape() {
        fun q(id: String, options: Int) = QuestionDto(
            id = id,
            text = "Prompt $id",
            options = (0 until options).map { OptionDto("o$it", "Answer $it") }
        )
        val file = McqFileDto(
            papers = listOf(
                PaperDto(
                    id = "p1",
                    title = "P",
                    questions = listOf(q("top", 1)),
                    categories = listOf(
                        CategoryDto(id = "c1", title = "C", questions = listOf(q("a", 1), q("b", 2), q("c", 0)))
                    )
                )
            )
        )

        assertEquals(2, file.countFlashcardConversions())
    }

    @Test
    fun anImageOnlyExplanationSurvivesTheMerge() {
        val normalized = oneOption(
            explanation = listOf(ContentElement.TextElement("")),
            explanationImage = "explanation.png"
        ).normalizedFlashcard()

        assertEquals("explanation.png", normalized.explanationImage)
        assertEquals(
            listOf(ContentElement.TextElement("Paris"), ContentElement.TextElement("")),
            normalized.explanationElements
        )
    }

    @Test
    fun aFlatOnlyExplanationIsNotLost() {
        val normalized = QuestionDto(
            id = "q1",
            text = "Capital of France?",
            options = listOf(OptionDto("a", "Paris")),
            correctOptionIds = listOf("a"),
            explanation = "It is the capital."
        ).normalizedFlashcard()

        assertEquals(
            listOf(
                ContentElement.TextElement("Paris"),
                ContentElement.TextElement("It is the capital.")
            ),
            normalized.explanationElements
        )
        assertNull(normalized.explanationImage)
    }
}
