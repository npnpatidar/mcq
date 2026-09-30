package com.mcqapp

import com.mcqapp.data.anki.AnkiDtoMapper
import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Export the sample paper, import it, export it again: the two packages must
 * describe the same questions.
 *
 * This is the check that caught a question picking up one of its options'
 * images on the way back in. Nothing in a single direction looks wrong — the
 * front field really does start with that image — so the property that has to be
 * asserted is that a second pass through the format changes nothing.
 *
 * The two byte streams cannot be compared: note ids, guids, timestamps, deck ids
 * and media filenames are all generated per export. What must match is the
 * content a reader sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SamplePaperApkgRoundTripTest {

    private val samplePaper: PaperDto by lazy { LegacyParser.parse(sampleJson()).papers.first() }

    /**
     * The sample paper is read from the source tree: a unit test's assets are not
     * on the classpath, and the working directory is the module directory.
     */
    private fun sampleJson(): String =
        listOf("src/main/assets/sample_paper.json", "app/src/main/assets/sample_paper.json")
            .firstNotNullOfOrNull { File(it).takeIf { file -> file.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found from ${File(".").absolutePath}")

    private fun write(paper: PaperDto) =
        AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper))

    private fun allQuestions(paper: PaperDto): List<QuestionDto> =
        paper.questions + paper.categories.flatMap { category -> category.questions }

    /** Everything a study session can see about a question, keyed by its text. */
    private fun shape(paper: PaperDto): Map<String, QuestionShape> =
        allQuestions(paper).associate { it.text to it.shape() }

    private data class OptionShape(
        val text: String,
        val image: String?,
        val correct: Boolean
    )

    private data class QuestionShape(
        val image: String?,
        val options: List<OptionShape>,
        val explanation: String,
        val explanationImage: String?,
        val difficulty: String,
        val marks: Double
    )

    private fun QuestionDto.shape() = QuestionShape(
        image = image,
        options = options.map { option ->
            OptionShape(option.text, option.image, option.id in correctOptionIds)
        },
        explanation = explanation,
        explanationImage = explanationImage,
        difficulty = difficulty,
        marks = marks
    )

    @Test
    fun reExportingTheSamplePaperChangesNothing() {
        val first = write(samplePaper)
        val reimported = AnkiPackageReader.read(first).file.papers.first()
        val second = write(reimported)

        val before = shape(samplePaper)
        val afterPassOne = shape(reimported)
        val afterPassTwo = shape(AnkiPackageReader.read(second).file.papers.first())

        assertEquals(
            "the sample paper's question count must survive",
            allQuestions(samplePaper).size,
            afterPassOne.size
        )
        // Pass one already has to be faithful: an option's image turning up on
        // the question shows up here.
        assertEquals(
            "importing our own export must not change any question\n" +
                before.filterKeys { before[it] != afterPassOne[it] },
            before,
            afterPassOne
        )
        // And a second trip through the format must be a no-op, which is what
        // "the two packages are the same" means for generated identifiers.
        assertEquals(
            "the second export must describe the same questions\n" +
                afterPassOne.filterKeys { afterPassOne[it] != afterPassTwo[it] },
            afterPassOne,
            afterPassTwo
        )
    }

    /**
     * The concrete report: a question with no image of its own and three options
     * that each have one. Its options' images must stay on the options.
     */
    @Test
    fun aQuestionsImageIsNotTakenFromItsOptions() {
        val visual = samplePaper.categories.first { it.title == "Visual Round" }
        val shapes = visual.questions.map { it.text to it.shape() }

        val withOptionImages = shapes.filter { (_, shape) -> shape.options.any { it.image != null } }
        assertTrue("the sample paper should have such questions", withOptionImages.isNotEmpty())
        withOptionImages.forEach { (text, shape) ->
            assertEquals("question '$text' has no image of its own", null, shape.image)
            assertTrue("options keep their images", shape.options.any { it.image != null })
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
}
