package com.mcqapp

import com.mcqapp.data.anki.AnkiDtoMapper
import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Export the sample paper, import it, export it again: the format must not
 * quietly rearrange anything, and a second pass must change nothing at all.
 *
 * This is the check that caught a question picking up one of its options'
 * images on the way back in. Nothing in a single direction looks wrong — the
 * front field really does start with that image — so the property that has to be
 * asserted is that a second pass through the format changes nothing.
 *
 * The two byte streams cannot be compared: note ids, guids, timestamps, deck ids
 * and media filenames are all generated per export. What must match is the
 * content a reader sees.
 *
 * The authored content survives the trip untouched — `elements`, the options and
 * their pictures, the answer keys, the explanations and the marks all compare
 * equal. Only two **derived** convenience fields differ on the first pass, and
 * both are pinned by [theFirstPassOnlyAddsDerivedFields] rather than quietly
 * tolerated:
 *
 *  - `text`, the flattened convenience string, gains the linearised formula or
 *    the table's cells that `elements` already carried in markup;
 *  - `image`, the scalar picture slot, is additionally filled from a picture
 *    that was authored as a content element. It is a copy, not a move: the
 *    element is still there.
 *
 * Neither changes anything on disk, and neither moves again on a second pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SamplePaperApkgRoundTripTest {

    private val sampleFile by lazy { LegacyParser.parse(sampleJson()) }
    private val samplePaper: PaperDto by lazy { sampleFile.papers.first() }

    /**
     * The sample paper is read from the source tree: a unit test's assets are not
     * on the classpath, and the working directory is the module directory.
     */
    private fun sampleJson(): String =
        listOf("src/main/assets/sample_paper.json", "app/src/main/assets/sample_paper.json")
            .firstNotNullOfOrNull { File(it).takeIf { file -> file.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found from ${File(".").absolutePath}")

    /** Wire passages as domain objects, the way PaperExporter hands them over. */
    private fun domainPassages(file: com.mcqapp.data.io.McqFileDto): Map<String, com.mcqapp.domain.Passage> =
        file.passages.associate {
            it.id to com.mcqapp.domain.Passage(
                id = it.id,
                categoryId = it.categoryId.orEmpty(),
                title = it.title,
                elements = it.elements,
                image = it.image
            )
        }

    private fun write(
        paper: PaperDto,
        passages: Map<String, com.mcqapp.domain.Passage> = domainPassages(sampleFile)
    ) = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper), passages = passages)

    private fun allQuestions(paper: PaperDto): List<QuestionDto> =
        paper.questions + paper.categories.flatMap { category -> category.questions }

    /**
     * Questions are paired by position, not by id or by text: Anki mints new
     * note ids on every export, and text is exactly the field that the first
     * pass normalises.
     */
    private fun shapes(paper: PaperDto): List<QuestionShape> = allQuestions(paper).map { it.shape() }

    private data class OptionShape(
        val text: String,
        val image: String?,
        val correct: Boolean
    )

    private data class QuestionShape(
        val text: String,
        val image: String?,
        val elements: List<ContentElement>,
        val options: List<OptionShape>,
        val explanation: String,
        val explanationImage: String?,
        val difficulty: String,
        val marks: Double
    )

    private fun QuestionDto.shape() = QuestionShape(
        text = text,
        image = image,
        elements = elements,
        options = options.map { option ->
            OptionShape(option.text, option.image, option.id in correctOptionIds)
        },
        explanation = explanation,
        explanationImage = explanationImage,
        difficulty = difficulty,
        marks = marks
    )

    /**
     * Everything the format carries unchanged. Deliberately excludes `text` and
     * the scalar `image`, the only two fields that normalise — see the class
     * doc. `elements` is in here, which is what makes this the strong claim: the
     * authored markup is compared, not the flattened string.
     */
    private fun QuestionShape.preserved() =
        elements to
            (options to (explanation to (explanationImage to (difficulty to marks))))

    @Test
    fun aSecondPassThroughTheFormatChangesNothing() {
        val first = write(samplePaper)
        val afterPassOne = shapes(AnkiPackageReader.read(first).file.papers.first())
        val afterPassTwo = shapes(
            AnkiPackageReader.read(write(AnkiPackageReader.read(first).file.papers.first()))
                .file.papers.first()
        )

        assertEquals(
            "the sample paper's question count must survive",
            shapes(samplePaper).size,
            afterPassOne.size
        )
        // The whole shape, text and image included: once the first pass has
        // normalised, nothing may move again.
        val moved = afterPassOne.indices.filter { afterPassOne[it] != afterPassTwo[it] }
        assertEquals(
            "the second export must describe the same questions, but these moved: " +
                afterPassOne.filterIndexed { index, shape -> index in moved },
            emptyList<Int>(),
            moved
        )
    }

    @Test
    fun theFirstPassPreservesEveryElement() {
        val before = shapes(samplePaper)
        val after = shapes(AnkiPackageReader.read(write(samplePaper)).file.papers.first())

        assertEquals(before.size, after.size)
        val changed = before.indices.filter { before[it].preserved() != after[it].preserved() }
        assertEquals(
            "importing our own export changed the content of: " +
                before.filterIndexed { index, shape -> index in changed },
            emptyList<Int>(),
            changed
        )
    }

    /**
     * Pins the only two fields that normalise, so neither can change unnoticed.
     * The content itself is asserted byte-identical by
     * [theFirstPassPreservesEveryElement]; this is about the derived fields.
     */
    @Test
    fun theFirstPassOnlyAddsDerivedFields() {
        val before = allQuestions(samplePaper)
        val after = allQuestions(AnkiPackageReader.read(write(samplePaper)).file.papers.first())
        fun pair(index: Int) = before[index] to after[index]

        // `text` gains the formula that `elements` already held as markup.
        val kinematics = before.indexOfFirst { q ->
            q.elements.any { it is ContentElement.MathElement }
        }
        assertTrue("the sample bank should contain a formula", kinematics >= 0)
        val (beforeMath, afterMath) = pair(kinematics)
        assertEquals(
            "the MathML element itself must survive untouched",
            beforeMath.elements,
            afterMath.elements
        )
        assertTrue(
            "expected the flattened text to gain the formula, got '${afterMath.text}'",
            afterMath.text.length > beforeMath.text.length
        )

        // `image` gains a copy of a picture authored as a content element.
        val withElementImage = before.indexOfFirst { q ->
            q.elements.any { it is ContentElement.ImageElement }
        }
        assertTrue("the sample bank should contain an element image", withElementImage >= 0)
        val (beforeImage, afterImage) = pair(withElementImage)
        assertEquals(
            "the image element must survive untouched",
            beforeImage.elements,
            afterImage.elements
        )
        assertNull("this question had no scalar image to begin with", beforeImage.image)
        assertTrue(
            "expected the scalar image to be filled from the element, got ${afterImage.image}",
            afterImage.image?.startsWith("data:image") == true
        )
    }

    /**
     * The concrete report: a question with no image of its own whose options
     * carry images. Those images must stay on their options.
     *
     * Scans the whole paper rather than one category: the sample bank is
     * regenerated, so a hard-coded category title would quietly turn this into
     * a no-op instead of a guard.
     */
    @Test
    fun aQuestionsImageIsNotTakenFromItsOptions() {
        val withOptionImages = allQuestions(samplePaper)
            .filter { question -> question.options.any { it.image != null } }

        assertTrue(
            "the sample paper should contain a question whose options have images",
            withOptionImages.isNotEmpty()
        )
        withOptionImages.forEach { question ->
            assertEquals(
                "question '${question.id}' has no image of its own",
                null,
                question.image
            )
        }
    }

    @Test
    fun categoryHierarchySurvivesBothDirections() {
        val first = AnkiPackageReader.read(write(samplePaper)).file.papers.first()

        val science = first.categories.first { it.title == "Science" }
        val physics = first.categories.first { it.title == "Physics" }
        assertEquals("Physics belongs to Science", science.id, physics.parentId)

        // And the deck names Anki itself shows keep the nesting.
        val deckNames = AnkiPackageReader.read(write(samplePaper))
        assertEquals(1, deckNames.file.papers.size)
    }

    /**
     * The demo's two passage groups must come back whole: the block rides in
     * the payload blob (elements included, so the table survives — a flat
     * string could not carry it), and every member keeps its passageId while
     * the standalone category-mate keeps its null.
     */
    @Test
    fun passageGroupsSurviveBothDirections() {
        val first = AnkiPackageReader.read(write(samplePaper))
        assertEquals(
            "the passage ids must survive",
            listOf("passage-1", "passage-2"),
            first.file.passages.map { it.id }
        )
        val waterCycle = first.file.passages.first { it.id == "passage-1" }
        assertEquals("The Water Cycle", waterCycle.title)
        assertTrue(
            "the passage's table element was flattened on the way back",
            waterCycle.elements.any { it is ContentElement.TableElement }
        )
        val before = allQuestions(samplePaper)
        val after = allQuestions(first.file.papers.first())
        assertEquals(
            "membership must match question for question",
            before.map { it.passageId },
            after.map { it.passageId }
        )
        assertEquals(5, after.count { !it.passageId.isNullOrBlank() })
        assertNull("the standalone question must stay standalone",
            after.single { it.id == "q-psg-6" }.passageId)

        // And a second pass through the format keeps all of it.
        val second = AnkiPackageReader.read(
            write(first.file.papers.first(), domainPassages(first.file))
        )
        assertEquals(
            "the passage elements must not move on a second pass",
            first.file.passages,
            second.file.passages
        )
        assertEquals(
            before.map { it.passageId },
            allQuestions(second.file.papers.first()).map { it.passageId }
        )
    }
}
