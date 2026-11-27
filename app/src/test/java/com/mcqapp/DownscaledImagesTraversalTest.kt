package com.mcqapp

import com.mcqapp.data.io.ImageDownscale
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.withDownscaledImages
import com.mcqapp.data.io.withScaledImages
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.CELL_IMAGE_MAX_DIMENSION
import com.mcqapp.util.cellImageTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val scaled = question.withScaledImages(fakeScaler, fakeScaler)
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
        val scaled = question.withScaledImages(fakeScaler, fakeScaler)
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
        val scaled = question.withScaledImages(fakeScaler, fakeScaler)
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
        val scaled = question.withScaledImages(fakeScaler, fakeScaler)
        assertEquals("scaled:question-scalar.png", scaled.image)
        assertEquals("scaled:explanation-scalar.png", scaled.explanationImage)
        assertEquals("scaled:option-scalar.png", scaled.options[0].image)
        assertEquals(null, scaled.options[1].image)
    }

    @Test
    fun tableCellImagesAreVisitedByTheScaler() {
        // A fake scaler prefixes non-URI text, which is not a picture any more;
        // the cell normaliser would (correctly) refuse it. Use a portable
        // rewrite instead, so the assertion proves the cell source reached the
        // scaler and came back into the stored tag.
        val cellScaler: (String?) -> String? = { src -> src?.replace("AAAA", "BBBB") }
        val withCellImage = question.copy(
            elements = listOf(
                ContentElement.TableElement(
                    listOf(
                        listOf("plain", """<img src="data:image/png;base64,AAAA">"""),
                        listOf("second row", "text only")
                    )
                )
            )
        )
        val scaled = withCellImage.withScaledImages(fakeScaler, cellScaler)
        val table = scaled.elements.single() as ContentElement.TableElement
        assertEquals("plain", table.rows[0][0])
        assertTrue(
            "the cell picture must reach the scaler: ${table.rows[0][1]}",
            table.rows[0][1].contains("data:image/png;base64,BBBB")
        )
        // Text-only cells are untouched, byte for byte.
        assertEquals(listOf("second row", "text only"), table.rows[1])
    }

    @Test
    fun everyImageInAMultiImageCellAndALaterRowIsVisitedAndCanonicalised() {
        val srcs = (1..3).map { "data:image/png;base64,${"A".repeat(it * 4)}" }
        val seen = mutableListOf<String?>()
        val cellScaler: (String?) -> String? = { src -> seen += src; src }
        val table = ContentElement.TableElement(
            listOf(
                listOf(
                    """<img src="${srcs[0]}">""",
                    "plain text",
                    """<img hidden src="${srcs[1]}">"""
                ),
                listOf(
                    "later row",
                    """<img/src="${srcs[2]}">"""
                )
            )
        )
        val scaled = question.copy(elements = listOf(table))
            .withScaledImages(fakeScaler, cellScaler)
            .elements.single() as ContentElement.TableElement

        assertEquals("every cell picture reaches the scaler", srcs, seen)
        assertEquals(cellImageTag(srcs[0]), scaled.rows[0][0])
        assertEquals("plain text", scaled.rows[0][1])
        assertEquals(cellImageTag(srcs[1]), scaled.rows[0][2])
        assertEquals("later row", scaled.rows[1][0])
        assertEquals(cellImageTag(srcs[2]), scaled.rows[1][1])
    }

    @Test
    fun aTextOnlyTableIsUnchangedByTheCellNormaliser() {
        val scaled = question.withScaledImages(fakeScaler, fakeScaler)
        assertEquals(
            ContentElement.TableElement(listOf(listOf("a", "b"))),
            scaled.elements[2]
        )
    }

    @Test
    fun nothingElseMoves() {
        val scaled = question.withScaledImages(fakeScaler, fakeScaler)
        assertEquals(question.text, scaled.text)
        assertEquals(question.difficulty, scaled.difficulty)
        assertEquals(question.marks, scaled.marks, 0.0)
        assertEquals(question.tags, scaled.tags)
        assertEquals(question.correctOptionIds, scaled.correctOptionIds)
        assertEquals(question.passageId, scaled.passageId)
        assertEquals(question.options[1].text, scaled.options[1].text)
    }

    @Test
    fun topLevelImagesUseTheDefaultCapAndCellsUseTheCellCap() {
        // The caps are wired here, not in the individual scalers: a top-level
        // picture keeps ImageDownscale's 1280, a cell uses 800. The seam skips
        // the bitmap work, so this is a pure-JVM assertion of the wiring.
        val src = "data:image/png;base64,AAAA"
        val dto = QuestionDto(
            id = "q1",
            text = "Q",
            elements = listOf(
                ContentElement.ImageElement(src),
                ContentElement.TableElement(listOf(listOf("""<img src="$src">""")))
            )
        )
        val caps = mutableListOf<Int>()
        val scaled = dto.withDownscaledImages { seen, maxDimension ->
            if (seen != null) caps += maxDimension
            seen
        }
        val table = scaled.elements[1] as ContentElement.TableElement

        assertEquals(listOf(ImageDownscale.DEFAULT_MAX_DIMENSION, CELL_IMAGE_MAX_DIMENSION), caps)
        assertEquals(1280, caps[0])
        assertEquals(800, caps[1])
        // The identity seam still leaves a canonical, drawable cell tag.
        assertEquals(cellImageTag(src), table.rows[0][0])
    }
}
