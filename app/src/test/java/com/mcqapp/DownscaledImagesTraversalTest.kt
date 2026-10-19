package com.mcqapp

import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.withScaledImages
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which image-bearing fields the question scaler walks.
 *
 * The real scaler decodes a bitmap, which no test runtime here can do
 * (Robolectric passes every image through untouched: see ImportAnswerUpdateTest),
 * so the traversal is asserted with a fake one. Every src it sees gains a
 * "scaled:" prefix, and nothing else on the question may move.
 */
class DownscaledImagesTraversalTest {

    private val fakeScaler: (String?) -> String? = { src -> src?.let { "scaled:$it" } }

    private val question = QuestionDto(
        id = "q1",
        text = "2 + 2?",
        elements = listOf(
            ContentElement.TextElement("2 + 2?"),
            ContentElement.ImageElement("question.png"),
            ContentElement.TableElement(listOf(listOf("a", "b")))
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
            ),
            OptionDto(id = "b", text = "five")
        ),
        correctOptionIds = listOf("a"),
        explanation = "because",
        explanationElements = listOf(
            ContentElement.MathElement("<math/>"),
            ContentElement.ImageElement("explanation.png")
        ),
        explanationImage = "explanation-scalar.png",
        difficulty = "hard",
        marks = 2.0,
        tags = listOf("algebra"),
        passageId = "p1"
    )

    @Test
    fun questionImageElementsAreScaled() {
        val scaled = question.withScaledImages(fakeScaler)
        assertEquals(
            listOf(
                ContentElement.TextElement("2 + 2?"),
                ContentElement.ImageElement("scaled:question.png"),
                ContentElement.TableElement(listOf(listOf("a", "b")))
            ),
            scaled.elements
        )
    }

    @Test
    fun explanationImageElementsAreScaled() {
        val scaled = question.withScaledImages(fakeScaler)
        assertEquals(
            listOf(
                ContentElement.MathElement("<math/>"),
                ContentElement.ImageElement("scaled:explanation.png")
            ),
            scaled.explanationElements
        )
    }

    @Test
    fun optionImageElementsAreScaled() {
        val scaled = question.withScaledImages(fakeScaler)
        assertEquals(
            listOf(
                ContentElement.TextElement("4"),
                ContentElement.ImageElement("scaled:option.png")
            ),
            scaled.options[0].elements
        )
        assertEquals(listOf("a", "b"), scaled.options.map { it.id })
        assertEquals(emptyList<ContentElement>(), scaled.options[1].elements)
    }

    @Test
    fun theScalarImageFieldsAreStillScaled() {
        val scaled = question.withScaledImages(fakeScaler)
        assertEquals("scaled:question-scalar.png", scaled.image)
        assertEquals("scaled:explanation-scalar.png", scaled.explanationImage)
        assertEquals("scaled:option-scalar.png", scaled.options[0].image)
        assertEquals(null, scaled.options[1].image)
    }

    @Test
    fun nothingElseMoves() {
        val scaled = question.withScaledImages(fakeScaler)
        assertEquals(question.text, scaled.text)
        assertEquals(question.difficulty, scaled.difficulty)
        assertEquals(question.marks, scaled.marks, 0.0)
        assertEquals(question.tags, scaled.tags)
        assertEquals(question.correctOptionIds, scaled.correctOptionIds)
        assertEquals(question.passageId, scaled.passageId)
        assertEquals(question.options[1].text, scaled.options[1].text)
    }
}
