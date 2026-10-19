package com.mcqapp

import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.toOptionEntities
import com.mcqapp.data.io.toPassageEntity
import com.mcqapp.data.io.toQuestionEntity
import com.mcqapp.data.io.withScaledImages
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.passageSearchText
import com.mcqapp.domain.parseContentElements
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the row *holds*, not just what the scaler walks.
 *
 * The scaler's output has to reach the store: the row's element JSON is what
 * [ContentHash.sameQuestionContent] reads back, so a row built from the file's
 * original bytes reads as changed on every re-import - and the full-size
 * picture survives the scaler after all. Bitmaps cannot be decoded in any test
 * runtime here (Robolectric passes every image through), so the twin is built
 * with a fake scaler and the rows are built from it.
 */
class ScaledRowStorageTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val fakeScaler: (String?) -> String? = { src -> src?.let { "scaled:$it" } }

    private val question = QuestionDto(
        id = "q1",
        text = "2 + 2?",
        elements = listOf(
            ContentElement.TextElement("2 + 2?"),
            ContentElement.ImageElement("question.png")
        ),
        image = "question-scalar.png",
        options = listOf(
            OptionDto(
                id = "a",
                text = "4",
                elements = listOf(
                    ContentElement.TextElement("4"),
                    ContentElement.ImageElement("option.png")
                ),
                image = "option-scalar.png"
            )
        ),
        correctOptionIds = listOf("a"),
        explanation = "because",
        explanationElements = listOf(
            ContentElement.TextElement("because"),
            ContentElement.ImageElement("explanation.png")
        ),
        explanationImage = "explanation-scalar.png",
        difficulty = "medium",
        marks = 2.0,
        tags = listOf("algebra"),
        passageId = "p1"
    )

    @Test
    fun theStoredQuestionRowHoldsTheScaledElementSrcs() {
        val scaled = question.withScaledImages(fakeScaler)
        val row = scaled.toQuestionEntity(
            categoryId = "c1",
            sortOrder = 3,
            contentHash = "hash",
            json = json
        )

        assertEquals(
            listOf(
                ContentElement.TextElement("2 + 2?"),
                ContentElement.ImageElement("scaled:question.png")
            ),
            row.text.parseContentElements(json)
        )
        assertEquals(
            listOf(
                ContentElement.TextElement("because"),
                ContentElement.ImageElement("scaled:explanation.png")
            ),
            row.explanation.parseContentElements(json)
        )
        assertEquals("scaled:question-scalar.png", row.image)
        assertEquals("scaled:explanation-scalar.png", row.explanationImage)
        assertEquals("q1", row.id)
        assertEquals("c1", row.categoryId)
        assertEquals("p1", row.passageId)
        assertEquals("2 + 2?", row.searchText)
        assertEquals(3, row.sortOrder)
        assertEquals("hash", row.contentHash)
        assertEquals("medium", row.difficulty)
        assertEquals(2.0, row.marks, 0.0)
        assertEquals("algebra", row.tags)
    }

    @Test
    fun theStoredOptionRowsHoldTheScaledElementSrcs() {
        val scaled = question.withScaledImages(fakeScaler)
        val rows = scaled.toOptionEntities(json)

        assertEquals(1, rows.size)
        assertEquals("a", rows[0].id)
        assertEquals("q1", rows[0].questionId)
        assertEquals(0, rows[0].sortOrder)
        assertEquals(
            listOf(
                ContentElement.TextElement("4"),
                ContentElement.ImageElement("scaled:option.png")
            ),
            rows[0].text.parseContentElements(json)
        )
        assertEquals("scaled:option-scalar.png", rows[0].image)
    }

    @Test
    fun theStoredRowIsWhatTheContentHashReadsBack() {
        val scaled = question.withScaledImages(fakeScaler)
        val row = scaled.toQuestionEntity(
            categoryId = "c1",
            sortOrder = 0,
            contentHash = "hash",
            json = json
        )
        val options = scaled.toOptionEntities(json)

        assertTrue(ContentHash.sameQuestionContent(row, options, scaled))
        // The same row read against the file's original bytes is a change -
        // which is exactly what building it from the unscaled DTO produces.
        assertFalse(ContentHash.sameQuestionContent(row, options, question))
    }

    @Test
    fun theStoredPassageRowHoldsTheScaledElementSrcs() {
        val passage = PassageDto(
            id = "p1",
            title = "Passage",
            elements = listOf(
                ContentElement.TextElement("once upon a time"),
                ContentElement.ImageElement("passage.png")
            ),
            image = "passage-scalar.png"
        )
        val scaled = passage.withScaledImages(fakeScaler)
        val row = scaled.toPassageEntity(categoryId = "c1", sortOrder = 2, json = json)

        assertEquals(
            listOf(
                ContentElement.TextElement("once upon a time"),
                ContentElement.ImageElement("scaled:passage.png")
            ),
            row.text.parseContentElements(json)
        )
        assertEquals("scaled:passage-scalar.png", row.image)
        assertEquals(passageSearchText("Passage", scaled.elements), row.searchText)
        assertEquals("Passage", row.title)
        assertEquals(2, row.sortOrder)
    }
}
