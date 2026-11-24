package com.mcqapp

import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Identity hashing. A ChemElement contributes nothing to [textContent], so it
 * used to hash to the empty string: two different structures with the same
 * (or no) text collided as duplicates. The `c` axis fixes that — while the
 * guard below pins the bytes of an ordinary question so no existing schedule
 * is churned by the change.
 */
class ContentHashTest {

    /** The digest this exact non-chemical question had before chemistry existed. */
    private val legacyHash = "355ceaac1cb25584f0a70b676cea3744bd8a197e019d08c45a224bd29f3d793b"

    private fun plain() = QuestionDto(
        id = "q1",
        text = "2 + 2?",
        options = listOf(OptionDto("a", "4"), OptionDto("b", "five"))
    )

    @Test
    fun aPlainQuestionKeepsItsHistoricalHash() {
        assertEquals(legacyHash, ContentHash.of(plain()))
    }

    @Test
    fun aStoredPlainQuestionKeepsTheSameHistoricalHash() {
        val question = Question(
            id = "q1",
            categoryId = "c1",
            text = "2 + 2?",
            options = listOf(QuestionOption("a", "4"), QuestionOption("b", "five")),
            correctOptionIds = emptySet()
        )
        assertEquals(legacyHash, ContentHash.of(question))
    }

    @Test
    fun twoDifferentStructuresDoNotCollide() {
        fun dto(smiles: String) = QuestionDto(
            id = "q",
            elements = listOf(ContentElement.ChemElement(smiles = smiles)),
            options = listOf(OptionDto("a", "4"))
        )
        assertNotEquals(
            "structures with distinct SMILES are distinct prompts",
            ContentHash.of(dto("c1ccccc1")),
            ContentHash.of(dto("CC(=O)O"))
        )
    }

    @Test
    fun aChemOnlyPromptHashesTheSameFromTheStoredShape() {
        val dto = QuestionDto(
            id = "q-chem",
            elements = listOf(
                ContentElement.TextElement("Name this:"),
                ContentElement.ChemElement(
                    smiles = "c1ccccc1", formula = "C6H6", caption = "Benzene"
                )
            ),
            options = listOf(OptionDto("a", "4"))
        )
        // The importer fills a DTO's flat option text into one text element;
        // that is the shape a stored row loads back as.
        val loaded = Question(
            id = dto.id,
            categoryId = "c1",
            elements = dto.elements,
            options = dto.options.map { option ->
                QuestionOption(
                    option.id,
                    option.elements.ifEmpty { listOf(ContentElement.TextElement(option.text)) },
                    option.image
                )
            },
            correctOptionIds = emptySet()
        )
        assertEquals(ContentHash.of(dto), ContentHash.of(loaded))
    }

    @Test
    fun aChangedChemBackIsStillTheSamePrompt() {
        // One prompt = one answer: the back is compared by CardContent, not by
        // the identity hash, so an edited chemical answer refreshes in place
        // instead of inserting a twin question.
        fun dto(smiles: String) = QuestionDto(
            id = "q-chem",
            elements = listOf(ContentElement.TextElement("Draw it")),
            options = listOf(OptionDto("a", "4")),
            explanationElements = listOf(ContentElement.ChemElement(smiles = smiles))
        )
        assertEquals(
            ContentHash.of(dto("c1ccccc1")),
            ContentHash.of(dto("CC(=O)O"))
        )
    }
}
