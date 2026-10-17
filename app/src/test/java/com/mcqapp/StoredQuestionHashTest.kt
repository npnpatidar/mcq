package com.mcqapp

import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One question, one content hash — whichever shape it is read in.
 *
 * The importer writes a question's `contentHash`; the study layer recomputes it
 * from the question it loads and reads a difference as "the content was
 * edited", so it resets the card and offers it again. If the two disagree the
 * same question comes back forever: the review counter climbs while the card
 * never changes. That is what an option picture folded into `options_elements`
 * used to do — the bundled bank's `q-vis-*` questions carry their picture as an
 * image element with no scalar mirror.
 *
 * A loaded question is modelled the way the database round trip shapes it: the
 * stored text column holds the elements JSON, so the domain's elements and
 * image are the DTO's.
 */
class StoredQuestionHashTest {

    /**
     * The shape a stored row loads back as. The importer and `saveQuestion`
     * write the elements JSON, filling a flat text field in as a text element
     * when the file carried only that — so an empty elements list comes back
     * with one.
     */
    private fun loaded(dto: QuestionDto) = Question(
        id = dto.id,
        categoryId = "cat",
        elements = dto.elements.ifEmpty { listOf(ContentElement.TextElement(dto.text)) },
        image = dto.image,
        options = dto.options.map { o ->
            QuestionOption(
                o.id,
                o.elements.ifEmpty { listOf(ContentElement.TextElement(o.text)) },
                o.image
            )
        },
        correctOptionIds = dto.correctOptionIds.toSet(),
        passageId = dto.passageId
    )

    /** The hash the importer and the import preview both write. */
    private fun imported(dto: QuestionDto) = ContentHash.of(dto)

    /** The hash the study layer derives from the loaded question. */
    private fun studySide(dto: QuestionDto) = ContentHash.of(loaded(dto))

    private fun assertOneHash(message: String, dto: QuestionDto) =
        assertEquals(message, imported(dto), studySide(dto))

    @Test
    fun aPlainQuestionHashesTheSameBothWays() {
        assertOneHash(
            "no pictures involved, so nothing may drift",
            QuestionDto(
                id = "q1",
                text = "2 + 2?",
                options = listOf(OptionDto("a", "4"), OptionDto("b", "five"))
            )
        )
    }

    /**
     * [LegacyParser] and the exporter both fill the flat `text` from the
     * elements, which is why only the picture axis ever drifted. A DTO that
     * carries elements without the flat field must still hash the same.
     */
    @Test
    fun elementsWithoutTheFlatTextFieldHashTheSameBothWays() {
        assertOneHash(
            "an element-only file must hash like the stored row it becomes",
            QuestionDto(
                id = "q2",
                elements = listOf(ContentElement.TextElement("Which shape?")),
                options = listOf(
                    OptionDto(id = "a", elements = listOf(ContentElement.TextElement("Square"))),
                    OptionDto(id = "b", elements = listOf(ContentElement.TextElement("Circle")))
                )
            )
        )
    }

    @Test
    fun anOptionPictureInsideTheElementsHashesTheSameBothWays() {
        val dto = QuestionDto(
            id = "q-vis-1",
            elements = listOf(ContentElement.TextElement("Which two shapes are quadrilaterals?")),
            options = listOf(
                OptionDto(
                    id = "a",
                    text = " Rectangle",
                    elements = listOf(
                        ContentElement.ImageElement("<img src=\"data:image/png;base64,AAAA\" alt=\"A rectangle\">"),
                        ContentElement.TextElement(" Rectangle")
                    )
                ),
                OptionDto(id = "b", text = "Circle", elements = listOf(ContentElement.TextElement("Circle")))
            )
        )
        // What the study side used to compute: the scalar image alone, which
        // reads the picture as "null" when it lives in the elements.
        assertNotEquals(
            "the scalar-only list is not this question's hash",
            ContentHash.of(
                dto.text,
                dto.options.map { it.text },
                dto.options.map { it.image },
                dto.passageId
            ),
            imported(dto)
        )
        assertOneHash("the picture sits in the elements, as an imported file writes it", dto)
    }

    @Test
    fun anOptionPictureInTheScalarHashesTheSameBothWays() {
        assertOneHash(
            "the editor and the database keep the picture in the scalar",
            QuestionDto(
                id = "q3",
                text = "Which shape?",
                options = listOf(
                    OptionDto("a", "Square", image = "square.png"),
                    OptionDto("b", "Circle")
                )
            )
        )
    }

    @Test
    fun anOptionCarryingBothShapesOfPictureHashesTheSameBothWays() {
        assertOneHash(
            "element srcs and the scalar are all part of the option's picture",
            QuestionDto(
                id = "q4",
                text = "Which one?",
                options = listOf(
                    OptionDto(
                        id = "a",
                        text = "One",
                        elements = listOf(
                            ContentElement.TextElement("One"),
                            ContentElement.ImageElement("one.png"),
                            ContentElement.ImageElement("two.png")
                        ),
                        image = "three.png"
                    ),
                    OptionDto("b", "Two")
                )
            )
        )
    }

    @Test
    fun aStemPictureHashesTheSameBothWays() {
        assertOneHash(
            "a picture on the stem must not drift either",
            QuestionDto(
                id = "q5",
                text = "Look at the figure",
                elements = listOf(
                    ContentElement.TextElement("Look at the figure"),
                    ContentElement.ImageElement("figure.png")
                ),
                options = listOf(OptionDto("a", "4"), OptionDto("b", "5"))
            )
        )
    }

    @Test
    fun aPassageMembershipStaysPartOfTheHash() {
        val question = loaded(QuestionDto(id = "q6", text = "In the passage?", options = listOf(OptionDto("a", "x"))))
        assertNotEquals(
            "moving a question between passages must read as edited content",
            ContentHash.of(question),
            ContentHash.of(question.copy(passageId = "p1"))
        )
    }

    private fun bundledBank(): List<QuestionDto> {
        val text = listOf(
            "src/main/assets/sample_paper.json",
            "app/src/main/assets/sample_paper.json"
        ).firstNotNullOfOrNull { File(it).takeIf { f -> f.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found from ${File(".").absolutePath}")
        return LegacyParser.parse(text).papers.flatMap { paper ->
            paper.categories.flatMap { it.questions } + paper.questions
        }
    }

    /**
     * The bank that hit this: its `q-vis-*` questions carry an option picture
     * as an image element with no scalar mirror. Every one of its questions
     * must hash the same once stored, or studying it resets the card as edited
     * and serves that same question forever.
     */
    @Test
    fun everyQuestionInTheBundledBankHashesTheSameBothWays() {
        val questions = bundledBank()
        assertTrue("the bank must carry questions", questions.size > 10)
        assertTrue(
            "the bank must carry a picture inside an option's elements",
            questions.any { q ->
                q.options.any { o ->
                    o.image == null && o.elements.any { it is ContentElement.ImageElement }
                }
            }
        )
        questions.forEach { dto ->
            assertEquals("${dto.id} hashes differently once stored", imported(dto), studySide(dto))
        }
    }
}
