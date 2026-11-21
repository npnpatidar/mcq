package com.mcqapp

import com.mcqapp.data.io.FALLBACK_CLOZE_EXPLANATION
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.backFromSingleOption
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one migration/import rule: a single option becomes a flashcard's answer
 * and the old explanation follows it, unless that explanation was only filler.
 */
class FlashcardConversionTest {

    @Test
    fun theOptionTextBecomesTheBackWhenOnlyTextIsKnown() {
        assertEquals(
            listOf(ContentElement.TextElement("Paris")),
            backFromSingleOption(
                optionElements = emptyList(),
                optionText = "Paris",
                explanationElements = emptyList(),
                explanationImage = null
            )
        )
    }

    @Test
    fun theOptionBlocksArePreferredOverFlatText() {
        val answer = listOf(
            ContentElement.TextElement("Paris"),
            ContentElement.ImageElement("map.png")
        )
        assertEquals(
            answer,
            backFromSingleOption(
                optionElements = answer,
                optionText = "Paris",
                explanationElements = emptyList(),
                explanationImage = null
            )
        )
    }

    @Test
    fun anExistingExplanationIsAppendedAfterTheAnswer() {
        val explanation = listOf(ContentElement.TextElement("It is the capital."))
        assertEquals(
            listOf(ContentElement.TextElement("Paris")) + explanation,
            backFromSingleOption(
                optionElements = emptyList(),
                optionText = "Paris",
                explanationElements = explanation,
                explanationImage = null
            )
        )
    }

    @Test
    fun aBlankExplanationIsReplacedRatherThanAppended() {
        val answer = listOf(ContentElement.TextElement("Paris"))
        assertEquals(
            answer,
            backFromSingleOption(
                optionElements = emptyList(),
                optionText = "Paris",
                explanationElements = emptyList(),
                explanationImage = null
            )
        )
        assertEquals(
            answer,
            backFromSingleOption(
                optionElements = emptyList(),
                optionText = "Paris",
                explanationElements = listOf(ContentElement.TextElement("")),
                explanationImage = null
            )
        )
    }

    @Test
    fun theAnkiClozePlaceholderIsReplacedRatherThanAppended() {
        val answer = listOf(ContentElement.TextElement("Paris"))
        assertEquals(
            answer,
            backFromSingleOption(
                optionElements = emptyList(),
                optionText = "Paris",
                explanationElements = listOf(ContentElement.TextElement(FALLBACK_CLOZE_EXPLANATION)),
                explanationImage = null
            )
        )
    }

    @Test
    fun aRealBlockBesideThePlaceholderKeepsEverything() {
        val explanation = listOf(
            ContentElement.TextElement(FALLBACK_CLOZE_EXPLANATION),
            ContentElement.TableElement(listOf(listOf("France", "Paris")))
        )
        assertEquals(
            listOf(ContentElement.TextElement("Paris")) + explanation,
            backFromSingleOption(
                optionElements = emptyList(),
                optionText = "Paris",
                explanationElements = explanation,
                explanationImage = null
            )
        )
    }

    @Test
    fun aPictureOnlyExplanationIsARealBack() {
        assertEquals(
            listOf(ContentElement.TextElement("Paris"), ContentElement.TextElement("")),
            backFromSingleOption(
                optionElements = emptyList(),
                optionText = "Paris",
                explanationElements = listOf(ContentElement.TextElement("")),
                explanationImage = "back.png"
            )
        )
    }

    @Test
    fun aBlankOptionFallsBackSafely() {
        assertEquals(
            listOf(ContentElement.TextElement("")),
            backFromSingleOption(
                optionElements = emptyList(),
                optionText = "",
                explanationElements = emptyList(),
                explanationImage = null
            )
        )
        assertEquals(
            listOf(ContentElement.TextElement("")),
            backFromSingleOption(
                optionElements = listOf(ContentElement.TextElement("")),
                optionText = "",
                explanationElements = emptyList(),
                explanationImage = null
            )
        )
    }
}
