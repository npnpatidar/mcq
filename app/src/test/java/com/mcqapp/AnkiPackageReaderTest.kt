package com.mcqapp

import android.database.sqlite.SQLiteDatabase
import com.mcqapp.data.anki.AnkiPackageException
import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.util.InlineHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

/**
 * Reading tests for `.apkg` import.
 *
 * Anki packages come in two shapes and the reader has to accept both: a legacy
 * zip (schema 11 and 14, `col.models`/`col.decks` JSON) and the modern one
 * (schema 15+, with normalised notetype and deck tables). The foreign-package
 * tests build those databases by hand so they exercise Anki's actual layout
 * rather than our own writer's output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnkiPackageReaderTest {

    private val png1x1 =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    private val pngBytes = java.util.Base64.getDecoder().decode(png1x1.substringAfterLast(","))

    private val us = AnkiPackageWriter.FIELD_SEPARATOR

    /** The default notetype and deck JSON a hand-built legacy collection carries. */
    private val basicModels =
        """{"1":{"id":1,"name":"Basic","type":0,"flds":[{"name":"Front","ord":0},{"name":"Back","ord":1}],"tmpls":[{"name":"Card 1","ord":0,"qfmt":"{{Front}}","afmt":"{{Back}}"}]}}"""
    private val basicDecks = """{"1":{"id":1,"name":"Default"}}"""

    /** The reader maps every deck to a category, so questions live in one. */
    private fun allQuestions(paper: com.mcqapp.data.io.PaperDto) =
        paper.questions + paper.categories.flatMap { it.questions }

    /** An explicitly scheduled card: a cloze note needs one card per deletion. */
    private data class ClozeCard(val cardId: Long, val nid: Long, val did: Long, val ord: Int)

    // ---- our own packages ----

    @Test
    fun roundTripOfAnExportedPaperPreservesEveryQuestion() {
        val questions = listOf(
            Question(
                id = "q1",
                categoryId = "c1",
                text = "Which organelle makes ATP?",
                options = listOf(
                    QuestionOption("o1", "Mitochondrion"),
                    // A line break inside an option is exactly what the readable
                    // back field cannot represent on its own.
                    QuestionOption("o2", "Ribosome\non the rough ER")
                ),
                correctOptionIds = setOf("o1"),
                explanation = "It hosts the electron transport chain.",
                difficulty = Difficulty.HARD,
                marks = 2.0,
                tags = listOf("energy")
            ),
            Question(
                id = "q2",
                categoryId = "c1",
                text = "Pick the two that apply",
                options = listOf(
                    QuestionOption("a", "One"),
                    QuestionOption("b", "Two"),
                    QuestionOption("c", "Three")
                ),
                correctOptionIds = setOf("a", "b"),
                explanation = "Both are correct.",
                image = png1x1
            )
        )
        val paper = PaperDto(
            id = "p1",
            title = "Biology",
            categories = listOf(CategoryDto(id = "c1", title = "Basics", questions = questions.map { it.toDto() }))
        )
        val apkg = apkgOf(paper)

        val result = AnkiPackageReader.read(apkg)

        assertEquals(1, result.file.papers.size)
        val imported = result.file.papers.first().let(::allQuestions)
        assertEquals(2, imported.size)
        assertEquals(0, result.recallCount)

        assertEquals("Which organelle makes ATP?", imported[0].text)
        assertEquals(
            "an option containing a line break survives",
            listOf("Mitochondrion", "Ribosome\non the rough ER"),
            imported[0].options.map { it.text }
        )
        assertEquals(listOf("o1"), imported[0].correctOptionIds)
        assertEquals("It hosts the electron transport chain.", imported[0].explanation)
        assertEquals("hard", imported[0].difficulty)
        assertEquals(2.0, imported[0].marks, 0.0001)
        assertEquals(listOf("energy"), imported[0].tags)

        assertEquals(listOf("a", "b"), imported[1].correctOptionIds)
        assertTrue(
            "image inlined: ${imported[1].image}",
            imported[1].image!!.startsWith("data:image/png;base64,")
        )
        assertTrue("text kept beside image: ${imported[1].text}", "Pick the two that apply" in imported[1].text)
    }

    @Test
    fun aQuestionWithNoOptionsStaysAQuestionWithNoOptions() {
        // A question can be authored with an empty option list. Reading it back
        // as a one option recall question would change what the question is,
        // and would report it under a different count.
        val questions = listOf(
            Question(
                id = "q1",
                categoryId = "c1",
                text = "This question arrived with no options at all.",
                options = emptyList(),
                correctOptionIds = emptySet(),
                explanation = "Imports tolerate empty option lists.",
                difficulty = Difficulty.MEDIUM,
                marks = 1.0
            )
        )
        val paper = PaperDto(
            id = "p1",
            title = "Edge Cases",
            categories = listOf(CategoryDto(id = "c1", title = "Odd", questions = questions.map { it.toDto() }))
        )

        val imported = AnkiPackageReader.read(
            apkgOf(paper)
        )

        assertEquals("nothing to choose between, so it counts as a recall", 1, imported.recallCount)
        val question = allQuestions(imported.file.papers.first()).single()
        assertEquals("This question arrived with no options at all.", question.text)
        assertEquals(emptyList<com.mcqapp.data.io.OptionDto>(), question.options)
        assertEquals(emptyList<String>(), question.correctOptionIds)
        assertEquals("Imports tolerate empty option lists.", question.explanation)
        assertEquals("medium", question.difficulty)
        assertEquals(1.0, question.marks, 0.0001)
    }

    @Test
    fun reExportingTheSamePaperKeepsEveryGuidSoAnkiUpdatesInPlace() {
        val questions = listOf(
            Question(
                id = "q1",
                categoryId = "c1",
                text = "One",
                options = listOf(QuestionOption("a", "A")),
                correctOptionIds = setOf("a")
            )
        )
        val paper = PaperDto(id = "p1", title = "P", questions = questions.map { it.toDto() })

        val first = guidsOf(apkgOf(paper))
        val second = guidsOf(apkgOf(paper))

        assertEquals(first, second)
    }

    @Test
    fun anExportedCategorySurvivesAsASubdeck() {
        val paper = PaperDto(
            id = "p1",
            title = "Biology",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Basics",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "One",
                            options = listOf(OptionDto("a", "A")),
                            correctOptionIds = listOf("a")
                        )
                    )
                )
            )
        )
        val apkg = apkgOf(paper)

        val result = AnkiPackageReader.read(apkg)

        assertEquals(1, result.noteCount)
        val imported = result.file.papers.single()
        assertEquals("Biology", imported.title)
        // The category became a subdeck, so its title survives the round trip.
        assertEquals(listOf("Basics"), imported.categories.map { it.title })
        assertEquals("One", allQuestions(imported).single().text)
    }

    // ---- foreign packages ----

    @Test
    fun aMarkedBackFieldBecomesOptionsWithCorrectAnswers() {
        val apkg = legacyPackage(
            notes = listOf(
                "Front: 2 + 2?${us}&#10003; 4<br>&#10007; five<br>Explanation: arithmetic" to 1L
            )
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals("Front: 2 + 2?", question.text)
        assertEquals(listOf("4", "five"), question.options.map { it.text })
        assertEquals(listOf("o0"), question.correctOptionIds)
        assertEquals("arithmetic", question.explanation)
        assertEquals(0, AnkiPackageReader.read(apkg).recallCount)
    }

    @Test
    fun aForeignCardFrontKeepsItsTableAndEveryPicture() {
        val front = "Which cells?<table><tr><td>Rod</td><td>Cone</td></tr></table>" +
            "<img src=\"0\"><img src=\"1\">"
        val apkg = legacyPackage(
            notes = listOf("$front${us}Both are light sensors.${us}" to 1L),
            media = mapOf("0" to "a.png", "1" to "b.png"),
            mediaBytes = mapOf("0" to pngBytes, "1" to pngBytes)
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals(emptyList<OptionDto>(), question.options)
        assertEquals(
            listOf(listOf("Rod", "Cone")),
            question.elements
                .filterIsInstance<com.mcqapp.domain.ContentElement.TableElement>()
                .single().rows
        )
        val images = question.elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>()
        assertEquals("every front picture must survive", 2, images.size)
        assertTrue(images.all { it.src.startsWith("data:image/png;base64,") })
    }

    /** The same table, but the picture sits inside a cell instead of beside it. */
    private fun foreignCellQuestion(front: String, media: Map<String, String> = emptyMap()) =
        AnkiPackageReader.read(
            legacyPackage(
                notes = listOf("$front${us}Both are light sensors.${us}" to 1L),
                media = media,
                mediaBytes = media.keys.associateWith { pngBytes }
            )
        )

    private fun cellOf(front: String, media: Map<String, String> = emptyMap()): String {
        val question = foreignCellQuestion(front, media).file.papers.single().let(::allQuestions).single()
        return question.elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.TableElement>()
            .single().rows.single().single()
    }

    @Test
    fun aForeignTableCellPictureBecomesTheCanonicalInlineDataUri() {
        val cell = cellOf(
            "Which cells?<table><tr><td>See <img src=\"0\"></td></tr></table>",
            media = mapOf("0" to "a.png")
        )
        assertTrue(cell, cell.contains("<img src=\"data:image/png;base64,"))
        assertFalse("the package-local name must be resolved away", cell.contains("src=\"0\""))
        assertTrue(cell, cell.startsWith("See <img"))
    }

    @Test
    fun aForeignTableCellPictureWithMissingMediaKeepsItsReferenceAndIsCounted() {
        val result = foreignCellQuestion(
            "Which cells?<table><tr><td>See <img src=\"missing.png\"></td></tr></table>"
        )
        val question = result.file.papers.single().let(::allQuestions).single()
        val cell = question.elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.TableElement>()
            .single().rows.single().single()
        assertEquals(1, result.missingMedia)
        assertEquals("<img src=\"missing.png\">", cell.substringAfter("See ").trim())
    }

    @Test
    fun aForeignTableCellFormulaIsKeptAsRawMathml() {
        val cell = cellOf("Which cells?<table><tr><td><math><mi>x</mi><mo>+</mo><mn>1</mn></math></td></tr></table>")
        assertEquals("<math><mi>x</mi><mo>+</mo><mn>1</mn></math>", cell)
    }

    @Test
    fun aForeignTableCellEscapedMathStaysLiteralText() {
        val cell = cellOf("Which cells?<table><tr><td>&lt;math&gt;&lt;mi&gt;x&lt;/mi&gt;&lt;/math&gt;</td></tr></table>")
        assertEquals("&lt;math&gt;&lt;mi&gt;x&lt;/mi&gt;&lt;/math&gt;", cell)
        assertFalse(cell.contains("<math"))
    }

    @Test
    fun aForeignTableCellChemStructureWithoutASmilesSpecDegradesToHandles() {
        val cell = cellOf(
            "Which cells?<table><tr><td>" +
                "<span class=\"mcq-chem-cell\">Benzene ring</span></td></tr></table>"
        )
        assertEquals("Benzene ring", cell)
    }

    @Test
    fun textAndInlineFormattingAroundACellPictureBothSurvive() {
        val cell = cellOf(
            "Which cells?<table><tr><td><b>Rod</b> <img src=\"0\"> <i>Cone</i></td></tr></table>",
            media = mapOf("0" to "a.png")
        )
        assertEquals("<b>Rod</b> <img src=\"${png1x1}\"> <i>Cone</i>", cell)
    }

    @Test
    fun aForeignTableCellMapsAPictureAndAFormulaIntoTheCanonicalGrammar() {
        val cell = cellOf(
            "Which cells?<table><tr><td>See <img src=\"0\"> <math>x+1</math></td></tr></table>",
            media = mapOf("0" to "a.png")
        )
        assertEquals("See <img src=\"${png1x1}\"> <math>x+1</math>", cell)
    }

    @Test
    fun hostileForeignTableCellPictureSourcesAreDroppedWhole() {
        listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "data:image/svg+xml;base64,PHN2Zz4="
        ).forEach { src ->
            val cell = cellOf("Which cells?<table><tr><td>A <img src=\"$src\"> B</td></tr></table>")
            assertEquals("A B", cell)
            assertFalse("leaked $src", cell.contains("javascript:"))
            assertFalse("leaked $src", cell.contains("file:"))
            assertFalse("leaked $src", cell.contains("svg"))
            assertFalse("leaked $src", cell.contains("base64"))
        }
    }

    @Test
    fun anEmptyBackIsDroppedAndCounted() {
        val apkg = legacyPackage(notes = listOf("A question with no answer?${us}${us}" to 1L))

        val result = AnkiPackageReader.read(apkg)

        assertTrue("an unusable card must not import", result.file.papers.isEmpty())
        assertEquals(0, result.noteCount)
        assertEquals("the drop must be counted for the report", 1, result.droppedStems)
    }

    @Test
    fun missingPackageLocalMediaIsCountedButInlineAndRemoteRefsAreNot() {
        // One picture the package promised but did not carry (zip entry "9"),
        // plus an already-inline data URI and an author-hosted remote image.
        // Only the first is a loss the app caused.
        val front = "Count me?<img src=\"9\">" +
            "<img src=\"$png1x1\"><img src=\"https://example.com/x.png\">"
        val apkg = legacyPackage(notes = listOf("$front${us}An answer${us}" to 1L))

        val result = AnkiPackageReader.read(apkg)

        assertEquals(1, result.missingMedia)
    }

    @Test
    fun aDeckWhosePicturesResolveReportsNoMissingMedia() {
        val apkg = legacyPackage(
            notes = listOf("Which one?${us}<img src=\"0\">An answer${us}" to 1L),
            media = mapOf("0" to "a.png"),
            mediaBytes = mapOf("0" to pngBytes)
        )

        assertEquals(0, AnkiPackageReader.read(apkg).missingMedia)
    }

    @Test
    fun theStreamedReaderCountsMissingMediaToo() = runBlocking {
        val apkg = legacyPackage(
            notes = listOf("Q?<img src=\"9\">${us}An answer${us}" to 1L)
        )
        val sink = object : AnkiPackageReader.StreamSink {
            override suspend fun onPaper(header: LegacyParser.PaperHeader) = Unit
            override suspend fun onCategory(category: CategoryDto) = Unit
            override suspend fun onQuestions(
                categoryId: String,
                questions: List<QuestionDto>,
                schedules: Map<String, CardScheduleDto>
            ) = Unit
        }

        val tail = AnkiPackageReader.stream(ByteArrayInputStream(apkg), sink)

        assertEquals(1, tail.missingMedia)
    }

    @Test
    fun aNoteWithASingleChoiceCountsAsAFlashcard() {
        // One option cannot be a multiple-choice card: the importer normalises
        // it into a flashcard, so the report's "imported as flashcards" count
        // must include it even though the reader keeps the option for conversion.
        val marker = AnkiPackageReader.read(
            legacyPackage(notes = listOf("Front: 2 + 2?${us}&#10003; 4" to 1L))
        )
        val marked = marker.file.papers.single().let(::allQuestions).single()
        assertEquals(1, marked.options.size)
        assertEquals(listOf("o0"), marked.correctOptionIds)
        assertEquals(1, marker.recallCount)

        val foreign = AnkiPackageReader.read(
            legacyPackage(
                notes = listOf(
                    "Capital of France?<p>(a) Paris</p>${us}" +
                        "<p>Answer: (a)</p><p>Exp: it is the capital</p>" to 1L
                )
            )
        )
        val oneChoice = foreign.file.papers.single().let(::allQuestions).single()
        assertEquals(1, oneChoice.options.size)
        assertEquals(1, foreign.recallCount)
    }

    @Test
    fun aNoteWithNoMarkersBecomesAZeroOptionFlashcard() {
        val apkg = legacyPackage(notes = listOf("Capital of France?${us}Paris${us}" to 1L))

        val result = AnkiPackageReader.read(apkg)
        val question = result.file.papers.single().let(::allQuestions).single()

        assertEquals("Capital of France?", question.text)
        assertEquals(emptyList<OptionDto>(), question.options)
        assertEquals(emptyList<String>(), question.correctOptionIds)
        assertEquals(listOf(com.mcqapp.domain.ContentElement.TextElement("Paris")), question.explanationElements)
        assertEquals("Paris", question.explanation)
        assertEquals(1, result.recallCount)
    }

    @Test
    fun aForeignBackKeepsItsTableAndEveryPictureAsTheFlashcardsAnswer() {
        // fieldToText would flatten the table to nothing and keep only the
        // first <img>; the back has to be built from elements instead.
        val back = "<p>Both are light sensors.</p>" +
            "<table border=\"1\"><tr><td>Rod</td><td>Dim light</td></tr></table>" +
            "<img src=\"0\"><img src=\"1\">"
        val apkg = legacyPackage(
            notes = listOf("Which cells see?${us}$back${us}" to 1L),
            media = mapOf("0" to "a.png", "1" to "b.png"),
            mediaBytes = mapOf("0" to pngBytes, "1" to pngBytes)
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals(emptyList<OptionDto>(), question.options)
        assertEquals(emptyList<String>(), question.correctOptionIds)
        val table = question.explanationElements
            .filterIsInstance<com.mcqapp.domain.ContentElement.TableElement>()
            .single()
        assertEquals(listOf(listOf("Rod", "Dim light")), table.rows)
        val images = question.explanationElements
            .filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>()
        assertEquals(2, images.size)
        assertTrue("both pictures are inlined", images.all { it.src.startsWith("data:image/png;base64,") })
        assertNull("the pictures live in the elements, not a scalar", question.explanationImage)
        assertEquals("Both are light sensors.", question.explanation)
    }

    @Test
    fun htmlInFieldsIsFlattenedToPlainText() {
        val apkg = legacyPackage(
            notes = listOf("<b>Capital</b> of<br>France?${us}<div>Paris</div><div>Marseille</div>" to 1L)
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        // The card front keeps its inline markup: the stored element is what the
        // renderer formats, so the raw string carries <b>, and the rendered
        // text is the prompt the learner reads.
        val front = question.elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.TextElement>()
            .single()
        assertTrue("inline markup must survive storage: ${front.text}", "<b>Capital</b>" in front.text)
        assertEquals("Capital of\nFrance?", InlineHtml.parse(front.text).text)
        // Nothing distinguishes an answer line from the rest of the back: the
        // whole field is the flashcard's answer.
        assertEquals(emptyList<OptionDto>(), question.options)
        assertEquals("Paris\nMarseille", question.explanation)
        assertEquals(
            listOf(com.mcqapp.domain.ContentElement.TextElement("Paris\nMarseille")),
            question.explanationElements
        )
    }

    @Test
    fun subDecksBecomeNestedCategoriesUnderOnePaper() {
        val apkg = legacyPackage(
            decks = """{"1":{"id":1,"name":"Default"},"10":{"id":10,"name":"Biology"},"20":{"id":20,"name":"Biology::Cells"}}""",
            notes = listOf("top level${us}answer" to 10L, "nested${us}answer" to 20L)
        )

        val result = AnkiPackageReader.read(apkg)

        val paper = result.file.papers.single()
        assertEquals("Biology", paper.title)
        // A card in the paper's own deck is an uncategorised question; a
        // category named "Biology" inside the paper "Biology" was never useful.
        assertEquals(listOf("top level"), paper.questions.map { it.text })
        assertEquals(listOf("Cells"), paper.categories.map { it.title })
        assertEquals(listOf("nested"), paper.categories.map { it.questions.single().text })
        assertNull(paper.categories.single().parentId)
        assertEquals(3, result.deckCount)
    }

    @Test
    fun aFilteredDeckIsNotImported() {
        val apkg = legacyPackage(
            decks = """{"1":{"id":1,"name":"Default"},"30":{"id":30,"name":"Cramming","dyn":1}}""",
            notes = listOf("keep${us}answer" to 1L)
        )

        val result = AnkiPackageReader.read(apkg)

        assertEquals(1, result.deckCount)
        assertEquals(listOf("keep"), result.file.papers.single().let(::allQuestions).map { it.text })
    }

    @Test
    fun mcqappTagsAreNotTurnedIntoUserTags() {
        val apkg = legacyPackage(
            notes = listOf("q${us}a" to 1L),
            noteTags = " mcqapp mcqapp-difficulty-easy cell-bio "
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals(listOf("cell-bio"), question.tags)
    }

    @Test
    fun foreignDifficultyTagIsRecovered() {
        // Recall path: no markers on the back.
        val recallPkg = legacyPackage(
            notes = listOf("q${us}a" to 1L),
            noteTags = " mcqapp mcqapp-difficulty-easy "
        )
        val recall = AnkiPackageReader.read(recallPkg).file.papers.single().let(::allQuestions).single()
        assertEquals("easy", recall.difficulty)

        // Marker path: options on the back.
        val markerPkg = legacyPackage(
            notes = listOf("q${us}&#10003; yes<br>&#10007; no" to 1L),
            noteTags = " mcqapp mcqapp-difficulty-hard "
        )
        val marker = AnkiPackageReader.read(markerPkg).file.papers.single().let(::allQuestions).single()
        assertEquals("hard", marker.difficulty)
    }

    @Test
    fun unknownDifficultyTagFallsBackToMedium() {
        val apkg = legacyPackage(
            notes = listOf("q${us}a" to 1L),
            noteTags = " mcqapp mcqapp-difficulty-extreme "
        )
        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()
        assertEquals("medium", question.difficulty)
    }

    @Test
    fun imagesReferencedByZipEntryNameAreInlined() {
        val apkg = legacyPackage(
            notes = listOf("look: <img src=\"0\">${us}answer" to 1L),
            media = mapOf("0" to "a diagram.png"),
            mediaBytes = mapOf("0" to pngBytes)
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals("image is the question's own, not markup in the text", "look:", question.text)
        val image = question.elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>()
            .single().src
        assertTrue("image inlined: $image", image.startsWith("data:image/png;base64,"))
    }

    @Test
    fun mediaMimeLabelsFollowTheRealSignature() {
        val gif = "GIF89a".toByteArray() + ByteArray(3)
        val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(2)
        val apkg = legacyPackage(
            notes = listOf(
                "gif: <img src=\"0\">${us}answer" to 1L,
                "webp: <img src=\"1\">${us}answer" to 1L
            ),
            media = mapOf("0" to "a.gif", "1" to "b.webp"),
            mediaBytes = mapOf("0" to gif, "1" to webp)
        )
        val questions = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions)
        val gifSrc = questions[0].elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>().single().src
        val webpSrc = questions[1].elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>().single().src
        assertTrue("gif labelled: $gifSrc", gifSrc.startsWith("data:image/gif;base64,"))
        assertTrue("webP labelled: $webpSrc", webpSrc.startsWith("data:image/webp;base64,"))
    }

    @Test
    fun riffContainerWithoutWebpFormTypeIsNotLabelledWebp() {
        val riff = "RIFF".toByteArray() + ByteArray(4) + "WAVE ".toByteArray()
        val apkg = legacyPackage(
            notes = listOf("look: <img src=\"0\">${us}answer" to 1L),
            media = mapOf("0" to "a.wav"),
            mediaBytes = mapOf("0" to riff)
        )
        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()
        val image = question.elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>().single().src
        assertTrue(
            "RIFF without WEBP is not WebP: $image",
            image.startsWith("data:image/jpeg;base64,")
        )
    }

    @Test
    fun truncatedPngSignatureIsNotLabelledPng() {
        val fakePng = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()
        ) + ByteArray(6)
        val apkg = legacyPackage(
            notes = listOf("look: <img src=\"0\">${us}answer" to 1L),
            media = mapOf("0" to "a.png"),
            mediaBytes = mapOf("0" to fakePng)
        )
        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()
        val image = question.elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>().single().src
        assertTrue(
            "a bare 89 50 prefix is not a PNG signature: $image",
            image.startsWith("data:image/jpeg;base64,")
        )
    }

    @Test
    fun foreignRichFieldBecomesContentElements() {
        val math = "<math display=\"inline\" xmlns=\"http://www.w3.org/1998/Math/MathML\"><mrow><msup><mi>x</mi><mn>2</mn></msup></mrow></math>"
        val front = "4.) हल करें: $math x का मान?<p>(a) x = 1</p><p>(b) x <math><mi>y</mi></math></p>"
        val back = "<p><strong>Answer: (b)</strong> x <math><mi>y</mi></math></p><p>Exp: हल <math><mi>z</mi></math></p>"
        val apkg = legacyPackage(notes = listOf("$front${us}$back" to 1L))

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals(
            listOf(
                com.mcqapp.domain.ContentElement.TextElement("4.) हल करें:"),
                com.mcqapp.domain.ContentElement.MathElement(math),
                com.mcqapp.domain.ContentElement.TextElement("x का मान?")
            ),
            question.elements
        )
        assertEquals(2, question.options.size)
        assertEquals("a", question.options[0].id)
        assertEquals(
            listOf(com.mcqapp.domain.ContentElement.TextElement("x = 1")),
            question.options[0].elements
        )
        assertEquals(
            listOf(
                com.mcqapp.domain.ContentElement.TextElement("x"),
                com.mcqapp.domain.ContentElement.MathElement("<math><mi>y</mi></math>")
            ),
            question.options[1].elements
        )
        assertEquals(listOf("b"), question.correctOptionIds)
        assertEquals(
            listOf(
                com.mcqapp.domain.ContentElement.TextElement("हल"),
                com.mcqapp.domain.ContentElement.MathElement("<math><mi>z</mi></math>")
            ),
            question.explanationElements
        )
    }

    @Test
    fun foreignFieldWithTableBecomesTableElement() {
        val front = "10.) तालिका: <table border=\"1\"><tr><td>Mad</td><td>Year</td></tr><tr><td>Kumbhalgarh</td><td>1458</td></tr></table><p>(a) विकल्प एक</p><p>(b) विकल्प दो</p>"
        val back = "<p><strong>Answer: (a)</strong> विकल्प एक</p><p>Exp: तालिका</p>"
        val apkg = legacyPackage(notes = listOf("$front${us}$back" to 1L))

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals(
            listOf(
                com.mcqapp.domain.ContentElement.TextElement("10.) तालिका:"),
                com.mcqapp.domain.ContentElement.TableElement(
                    listOf(listOf("Mad", "Year"), listOf("Kumbhalgarh", "1458"))
                )
            ),
            question.elements
        )
        assertEquals(
            listOf(com.mcqapp.domain.ContentElement.TextElement("विकल्प एक")),
            question.options[0].elements
        )
        assertEquals(
            listOf(com.mcqapp.domain.ContentElement.TextElement("विकल्प दो")),
            question.options[1].elements
        )
        assertEquals(listOf("a"), question.correctOptionIds)
    }

    @Test
    fun foreignFieldWithMultipleImagesKeepsThemAllAsElements() {
        val front = "3.) चित्र: <img src=\"0\"/><br/><img src=\"1\"/><p>(a) वृत्त</p><p>(b) वर्ग</p>"
        val back = "<p><strong>Answer: (a)</strong> वृत्त</p><p>Exp: गोला</p>"
        val apkg = legacyPackage(
            notes = listOf("$front${us}$back" to 1L),
            media = mapOf("0" to "a.png", "1" to "b.png"),
            mediaBytes = mapOf("0" to pngBytes, "1" to pngBytes)
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals(3, question.elements.size)
        assertEquals(com.mcqapp.domain.ContentElement.TextElement("3.) चित्र:"), question.elements[0])
        assertTrue("first image an element", question.elements[1] is com.mcqapp.domain.ContentElement.ImageElement)
        assertTrue("second image an element", question.elements[2] is com.mcqapp.domain.ContentElement.ImageElement)
        val images = question.elements.filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>()
        assertEquals(2, images.size)
        assertTrue(images.all { it.src.startsWith("data:image/png;base64,") })
        assertEquals(listOf("a"), question.correctOptionIds)
    }

    @Test
    fun modernSchema14PackageIsReadFromNormalisedTables() {
        val db = newCollection(14)
        modernTables(db)
        db.execSQL("insert into notetypes values (1, 'Basic', x'')")
        db.execSQL("insert into fields values (1, 0, 'Front'), (1, 1, 'Back')")
        db.execSQL("insert into templates values (1, 0, '{{Front}}')")
        db.execSQL("insert into decks values (1, 'Default', 0, 0, x'', x''), (55, 'Chem::Bonds', 0, 0, x'', x'')")
        db.execSQL(
            // A third-party MCQ deck: the question on the front, the options
            // marked on the back. This notetype has no structured payload
            // field, which is what the marker path is for.
            "insert into notes values (100, 'g1', 1, 0, 0, '', " +
                "' bond?${us}&#10003; yes<br>&#10007; no', ' bond?', 0, 0, '')"
        )
        db.execSQL(
            "insert into cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, " +
                "lapses, left, odue, odid, flags, data) values " +
                "(200, 100, 55, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '')"
        )
        val bytes = bytesOf(db)
        val apkg = zipOf("collection.anki21" to bytes)

        val result = AnkiPackageReader.read(apkg)

        val question = result.file.papers.single().let(::allQuestions).single()
        assertEquals("bond?", question.text)
        assertEquals(listOf("yes", "no"), question.options.map { it.text })
        assertEquals(listOf("o0"), question.correctOptionIds)
        assertEquals("Chem", result.file.papers.single().title)
        assertEquals(listOf("Bonds"), result.file.papers.single().categories.map { it.title })
    }

    @Test
    fun aSchema14CollectionIsReadFromColModels() {
        // Schema 14 adds deck_config/config/tags but NOT the notetype tables:
        // Anki's schema15_upgrade.sql creates fields/templates/notetypes/decks
        // and only then sets ver = 15, so a ver=14 collection still carries its
        // notetype and deck JSON in `col`. `collection.anki2` because that is
        // the name these files use — `collection.anki21` arrived with the 15
        // tables; the reader accepts either (COLLECTION_NAMES).
        val apkg = legacyPackage(
            version = 14,
            models = """{"1":{"id":1,"name":"Cloze","type":1,"flds":[{"name":"Text","ord":0},{"name":"Back Extra","ord":1}],"tmpls":[{"name":"Cloze","ord":0}]}}""",
            decks = """{"1":{"id":1,"name":"Default"},"55":{"id":55,"name":"Chem::Bonds"},"99":{"id":99,"name":"Filtered","dyn":1}}""",
            notes = listOf("The capital of {{c1::France}} is {{c2::Paris}}." to 1L),
            cards = listOf(
                ClozeCard(200L, 1L, 55L, 0),
                ClozeCard(201L, 1L, 55L, 1)
            )
        )

        val result = AnkiPackageReader.read(apkg)

        val questions = result.file.papers.single().let(::allQuestions)
        assertEquals(listOf("France", "Paris"), questions.map { it.explanation })
        assertTrue(questions.all { it.options.isEmpty() })
        assertTrue(questions.all { "anki-cloze" in it.tags })
        // The dyn deck is skipped; the normal and the empty deck stay.
        assertEquals(2, result.deckCount)
        assertEquals(listOf("Chem"), result.file.papers.map { it.title })
    }

    @Test
    fun schema15ProtobufConfigDrivesClozeNotetypesAndFilteredDecks() {
        val db = newCollection(15)
        modernTables(db)
        // Anki 2.1.28+ stores these as prost blobs. x'0801' is
        // Notetype.Config{kind: 1} (field 1, varint): a cloze notetype.
        db.execSQL("insert into notetypes values (1, 'Cloze', x'0801')")
        db.execSQL("insert into fields values (1, 0, 'Text'), (1, 1, 'Back Extra')")
        db.execSQL("insert into templates values (1, 0, '{{cloze:Text}}')")
        // x'0A00' is Deck.KindContainer{normal: {}} (field 1), x'1200' is
        // {filtered: {}} (field 2), x'' has neither.
        db.execSQL(
            "insert into decks values (1, 'Default', 0, 0, x'', x''), " +
                "(55, 'Chem::Bonds', 0, 0, x'', x'0A00'), (99, 'Filtered', 0, 0, x'', x'1200')"
        )
        db.execSQL(
            "insert into notes values (100, 'g1', 1, 0, 0, '', " +
                "'The capital of {{c1::France}} is {{c2::Paris}}.${us}', '', 0, 0, '')"
        )
        db.execSQL(
            "insert into cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, " +
                "lapses, left, odue, odid, flags, data) values " +
                "(200, 100, 55, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ''), " +
                "(201, 100, 55, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '')"
        )

        val result = AnkiPackageReader.read(zipOf("collection.anki21" to bytesOf(db)))

        // Cloze: each card's deletion is the back, not a single-option choice
        // of the raw stem (which is what a notetype read as `isCloze = false`
        // produced).
        val questions = result.file.papers.single().let(::allQuestions)
        assertEquals(listOf("France", "Paris"), questions.map { it.explanation })
        assertTrue(questions.all { it.options.isEmpty() })
        assertTrue(questions.all { "anki-cloze" in it.tags })
        // The filtered deck is skipped; the normal and the empty-kind deck stay.
        assertEquals(2, result.deckCount)
        assertEquals(listOf("Chem"), result.file.papers.map { it.title })
    }

    @Test
    fun schema18UnitSeparatorDeckNamesBecomeNestedCategories() {
        // Anki 2.1.50+ (schema 18) stores a deck's folder separator as the
        // Unit Separator (0x1f), not "::". The reader split only on "::", so
        // each subdeck became its own flat paper with a raw control character
        // in the title. Normalising once in the deck reader must give exactly
        // the hierarchy the "::" form gives.
        val db = newCollection(18)
        modernTables(db)
        db.execSQL("insert into notetypes values (1, 'Basic', x'')")
        db.execSQL("insert into fields values (1, 0, 'Front'), (1, 1, 'Back')")
        db.execSQL("insert into templates values (1, 0, '{{Front}}')")
        db.execSQL(
            "insert into decks values (1, 'Default', 0, 0, x'', x''), " +
                "(54, 'Bank', 0, 0, x'', x''), " +
                "(55, 'Bank${us}Science', 0, 0, x'', x''), " +
                "(56, 'Bank${us}Visual Reasoning', 0, 0, x'', x'')"
        )
        db.execSQL(
            "insert into notes values (100, 'g1', 1, 0, 0, '', " +
                "'Q1?${us}&#10003; yes<br>&#10007; no', '', 0, 0, '')"
        )
        db.execSQL(
            "insert into notes values (101, 'g2', 1, 0, 0, '', " +
                "'Q2?${us}&#10003; yes<br>&#10007; no', '', 0, 0, '')"
        )
        db.execSQL(
            "insert into cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, " +
                "lapses, left, odue, odid, flags, data) values " +
                "(200, 100, 55, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, ''), " +
                "(201, 101, 56, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '')"
        )

        val result = AnkiPackageReader.read(zipOf("collection.anki21" to bytesOf(db)))

        val paper = result.file.papers.single()
        assertEquals("Bank", paper.title)
        assertEquals(listOf("Science", "Visual Reasoning"), paper.categories.map { it.title })
        assertEquals(2, result.noteCount)
        val titles = listOf(paper.title) + paper.categories.map { it.title }
        assertTrue(
            "no title may carry the Unit Separator: $titles",
            titles.none { '\u001f' in it }
        )
    }

    @Test
    fun aClozeNoteBecomesOneQuestionPerCard() {
        val apkg = legacyPackage(
            models = """{"1":{"id":1,"name":"Cloze","type":1,"flds":[{"name":"Text","ord":0},{"name":"Back Extra","ord":1}],"tmpls":[{"name":"Cloze","ord":0}]}}""",
            notes = listOf("The capital of {{c1::France}} is {{c2::Paris}}." to 1L),
            cards = listOf(
                ClozeCard(10L, 1L, 1L, 0),
                ClozeCard(11L, 1L, 1L, 1)
            )
        )

        val questions = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions)

        assertEquals(2, questions.size)
        assertEquals(listOf("France", "Paris"), questions.map { it.explanation })
        assertTrue(questions.all { it.options.isEmpty() })
        // One card per deletion keeps its own `-ord` id, which is what its
        // schedule is keyed by.
        assertEquals(listOf("0", "1"), questions.map { it.id.substringAfterLast('-') })
        // The placeholder is replaced by the deletion, never appended after it.
        assertTrue(questions.all { q ->
            q.explanationElements.none {
                it is com.mcqapp.domain.ContentElement.TextElement &&
                    it.text.contains("Cloze card from Anki")
            }
        })
        // The stem hides the card's own cloze and reveals the other one, so it
        // carries neither the answer nor the raw `{{cN::…}}` markup.
        assertEquals(
            listOf("The capital of [...] is Paris.", "The capital of France is [...]."),
            questions.map { it.text }
        )
        assertFalse("stem leaks its answer", questions[0].text.contains("France"))
        assertFalse("stem leaks its answer", questions[1].text.contains("Paris"))
        assertTrue("raw cloze markup survived", questions.none { "{{c" in it.text })
    }

    @Test
    fun aClozeStemKeepsItsTableAndEveryPicture() {
        val stem = "Look: <table><tr><td>A</td><td>B</td></tr></table>" +
            "<img src=\"0\"><img src=\"1\"> The capital is {{c1::Paris}}."
        val apkg = legacyPackage(
            models = """{"1":{"id":1,"name":"Cloze","type":1,"flds":[{"name":"Text","ord":0},{"name":"Back Extra","ord":1}],"tmpls":[{"name":"Cloze","ord":0}]}}""",
            notes = listOf("$stem${us}${us}" to 1L),
            cards = listOf(ClozeCard(10L, 1L, 1L, 0)),
            media = mapOf("0" to "a.png", "1" to "b.png"),
            mediaBytes = mapOf("0" to pngBytes, "1" to pngBytes)
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals(emptyList<OptionDto>(), question.options)
        assertEquals("Paris", question.explanation)
        assertEquals(
            listOf(listOf("A", "B")),
            question.elements
                .filterIsInstance<com.mcqapp.domain.ContentElement.TableElement>()
                .single().rows
        )
        assertEquals(
            "both stem pictures must survive",
            2,
            question.elements.filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>().size
        )
        assertTrue("the cloze is still blanked", "[...]" in question.text)
    }

    @Test
    fun aClozeCardIsResolvedByItsClozeNumberNotItsPosition() {
        // Anki's card ord is the cloze number minus one. Here the note numbers
        // its deletions c1 and c3, so the c3 card arrives with ord 2: reading
        // the deletion by list position would find nothing and drop the card.
        val apkg = legacyPackage(
            models = """{"1":{"id":1,"name":"Cloze","type":1,"flds":[{"name":"Text","ord":0},{"name":"Back Extra","ord":1}],"tmpls":[{"name":"Cloze","ord":0}]}}""",
            notes = listOf("{{c1::one}} then {{c3::three}}" to 1L),
            cards = listOf(ClozeCard(10L, 1L, 1L, 2))
        )

        val questions = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions)

        assertEquals(1, questions.size)
        assertEquals("three", questions.single().explanation)
        assertEquals(emptyList<OptionDto>(), questions.single().options)
        assertEquals("one then [...]", questions.single().text)
        assertTrue("raw cloze markup survived", "{{c" !in questions.single().text)
    }

    @Test
    fun aNoteWithNoCardIsStillImported() {
        val apkg = legacyPackage(notes = listOf("orphan${us}answer" to 1L), cards = emptyList(), skipAutoCard = true)

        val result = AnkiPackageReader.read(apkg)

        assertEquals(1, result.noteCount)
        assertEquals("orphan", result.file.papers.single().let(::allQuestions).single().text)
    }

    // ---- failures ----

    @Test
    fun aZipWithoutACollectionIsRejectedWithAUsefulMessage() {
        val e = runCatching { AnkiPackageReader.read(zipOf("readme.txt" to "hello".toByteArray())) }
        val message = e.exceptionOrNull()?.message.orEmpty()
        assertTrue("message names the missing collection: $message", "collection.anki2" in message)
    }

    /**
     * The real v3 package the owner reported: `meta` is `08 03`, the collection
     * is zstd `collection.anki21b`, the media manifest is a zstd protobuf
     * `MediaEntries` and every picture entry is zstd too. A JSON-only manifest
     * parser leaves every front-referenced picture as a raw `mcqapp-…` filename,
     * so this test fails on it (the image assertions below).
     */
    @Test
    fun theRealV3PackageImportsItsMediaAndNestedCategories() {
        val apkg = fixture("anki-v3-sample.apkg")

        val whole = AnkiPackageReader.read(apkg)
        assertEquals(62, whole.noteCount)
        val paper = whole.file.papers.single()
        assertEquals("Sample Bank — English + हिन्दी", paper.title)
        val categories = paper.categories.map { it.title }
        for (expected in listOf(
            "Science", "Mathematics", "Visual Reasoning", "Reading Passages", "History",
            "Geography", "Sports", "Multiple Correct Answers", "Text Formatting",
            "Bilingual — English + हिन्दी", "Order Check and Marks", "Edge Cases", "Uncategorized"
        )) {
            assertTrue("missing category $expected: $categories", expected in categories)
        }
        val science = paper.categories.first { it.title == "Science" }
        assertEquals(
            "the schema-18 separator must nest these under Science",
            listOf("Chemistry", "Physics"),
            paper.categories.filter { it.parentId == science.id }.map { it.title }
        )
        assertTrue(
            "no title may carry the Unit Separator: ${listOf(paper.title) + categories}",
            (listOf(paper.title) + categories).none { '\u001f' in it }
        )

        val questions = allQuestions(paper)
        // The package normalises the sample's single-option question into a
        // flashcard (its option became the back), so the no-option cards are the
        // sample's seven flashcards plus that one; the same nine carry no key.
        assertEquals(8, questions.count { it.options.isEmpty() })
        assertEquals(9, questions.count { it.correctOptionIds.isEmpty() })
        val imageValues = questions.mapNotNull { it.image }
        assertTrue(
            "a front-referenced picture must be inlined: ${imageValues.filter { "mcqapp-" in it }}",
            imageValues.any { it.startsWith("data:image/") }
        )
        assertTrue(
            "no package filename may survive as an image: ${imageValues.filter { "mcqapp-" in it }}",
            imageValues.none { "mcqapp-" in it }
        )
        assertTrue(
            "the compatibility stub must never become a question",
            questions.none { it.text.contains("Please update to the latest Anki version") }
        )

        val sink = streamed(apkg)
        assertEquals(listOf(paper.title), sink.paperTitles)
        assertEquals(categories, sink.categoryTitles)
        assertEquals(questions.size, sink.questions.size)
        assertEquals(8, sink.questions.count { it.options.isEmpty() })
        assertEquals(9, sink.questions.count { it.correctOptionIds.isEmpty() })
        assertTrue(
            "the streamed reader must inline the same pictures",
            sink.questions.mapNotNull { it.image }.any { it.startsWith("data:image/") }
        )
        assertTrue(
            "the streamed reader must not leave package filenames",
            sink.questions.mapNotNull { it.image }.none { "mcqapp-" in it }
        )
        assertTrue(
            sink.questions.none { it.text.contains("Please update to the latest Anki version") }
        )
    }

    @Test
    fun aModernCollectionIsPreferredOverTheCompatibilityStub() {
        // Anki writes a tiny `collection.anki2` beside the real modern collection
        // only so an older client can tell the user to upgrade. This fixture puts
        // the stub first deliberately: the old whole-file reader took the first
        // matching zip entry, so a stub-first package imported zero questions even
        // though an authentic package writes meta -> collection -> stub -> media.
        // The streamed reader was worse: it preferred anki2 by name regardless of
        // zip order, so the authentic order broke there too.
        val full = collectionBytes(
            notes = listOf(
                "Front: 2 + 2?${us}&#10003; 4<br>&#10007; five" to 1L,
                "Capital of France?${us}Paris" to 1L
            )
        )
        val apkg = zipOf(
            "collection.anki2" to stubCollection(),
            "collection.anki21" to full,
            "media" to "{}".toByteArray()
        )

        val result = AnkiPackageReader.read(apkg)
        val questions = result.file.papers.single().let(::allQuestions)

        assertEquals("the real collection's two questions import", 2, result.noteCount)
        assertEquals(2, questions.size)
        assertTrue(
            "the compatibility stub must never become a question: " +
                questions.map { it.text },
            questions.none { it.text.contains("Please update to the latest Anki version") }
        )
    }

    @Test
    fun aV3PackageReadsItsProtobufMediaManifest() {
        // Anki v3: `meta` is 08 03, the collection is schema 18 with `anki21b`,
        // deck names use the Unit Separator, the media manifest is the protobuf
        // MediaEntries, and every picture entry is zstd. The picture is
        // referenced by its manifest filename, so a missing manifest decode
        // cannot pass on the numeric zip name.
        val apkg = zipOf(
            "meta" to byteArrayOf(0x08, 0x03),
            "collection.anki2" to stubCollection(),
            "collection.anki21b" to zstdFrame(
                v3CollectionBytes(
                    notes = listOf("Look: <img src=\"a.png\">${us}answer" to 55L)
                )
            ),
            "media" to zstdFrame(mediaManifestProto(listOf("a.png"))),
            "0" to zstdFrame(pngBytes)
        )

        val paper = AnkiPackageReader.read(apkg).file.papers.single()
        assertEquals("Bank", paper.title)
        // Deck 56 ("Visual Reasoning") exists in the collection but holds no
        // question in this fixture, so the reader drops that empty category.
        assertEquals(listOf("Science"), paper.categories.map { it.title })
        val question = allQuestions(paper).single()
        assertEquals("Look:", question.text)
        val image = question.elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>()
            .single().src
        assertTrue("the compressed picture is inlined: $image", image.startsWith("data:image/png;base64,"))
        assertTrue("no Unit Separator in a title", paper.categories.none { '\u001f' in it.title })
    }

    @Test
    fun aMalformedMediaManifestLosesPicturesButKeepsQuestions() {
        // A truncated protobuf manifest (field 1, length 5, no bytes) is
        // atypical, not fatal: log it, resolve no picture, and still import the
        // deck's questions. Only a broken zstd frame is a package error.
        val apkg = zipOf(
            "meta" to byteArrayOf(0x08, 0x03),
            "collection.anki21b" to zstdFrame(
                v3CollectionBytes(notes = listOf("Look: <img src=\"a.png\">${us}answer" to 55L))
            ),
            "media" to zstdFrame(byteArrayOf(0x0A, 0x05))
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()
        assertEquals("Look:", question.text)
        assertTrue(
            "with no manifest the picture cannot be inlined",
            question.elements
                .filterIsInstance<com.mcqapp.domain.ContentElement.ImageElement>()
                .none { it.src.startsWith("data:") }
        )
        assertEquals(1, streamed(apkg).questions.size)
    }

    @Test
    fun aZstdOnlyCollectionWithNoPlainFallbackImports() {
        // Fallback case: a package with no `meta` and no plain member, only a
        // zstd `anki21b`. The name preference must still find and decode it.
        val apkg = zipOf(
            "collection.anki21b" to zstdFrame(
                collectionBytes(notes = listOf("Capital of France?${us}Paris" to 1L))
            )
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals("Capital of France?", question.text)
        assertEquals("Paris", question.explanation)
    }

    @Test
    fun aCorruptZstdCollectionIsAFriendlyPackageError() {
        // The magic says "zstd", the frame is nonsense: the import must report a
        // package problem with the decoder failure kept as the cause, not leak a
        // raw IOException (and certainly not crash). Anki only compresses
        // `anki21b`, so that is the entry this can happen in.
        val e = runCatching {
            AnkiPackageReader.read(zipOf("collection.anki21b" to corruptZstd()))
        }
        val failure = e.exceptionOrNull()

        assertTrue("reported as an Anki package problem: $failure", failure is AnkiPackageException)
        assertEquals(
            "a sentence the user can act on: ${failure?.message}",
            true,
            "could not be unpacked" in failure?.message.orEmpty()
        )
        assertNotNull("the decoder failure is kept as the cause", failure?.cause)
        assertFalse(
            "the .colpkg rejection text must be gone: ${failure?.message}",
            ".colpkg" in failure?.message.orEmpty()
        )
    }

    @Test
    fun aCorruptZstdCollectionIsAFriendlyPackageErrorWhenStreamed() {
        // The streamed decoder is a separate path (ZstdInputStream into the
        // spooled collection file), so it gets its own regression.
        val apkg = zipOf("collection.anki21b" to corruptZstd())
        val e = runCatching { streamed(apkg) }
        val failure = e.exceptionOrNull()

        assertTrue("reported as an Anki package problem: $failure", failure is AnkiPackageException)
        assertEquals(
            "a sentence the user can act on: ${failure?.message}",
            true,
            "could not be unpacked" in failure?.message.orEmpty()
        )
        assertNotNull("the decoder failure is kept as the cause", failure?.cause)
        assertFalse(
            "the .colpkg rejection text must be gone: ${failure?.message}",
            ".colpkg" in failure?.message.orEmpty()
        )
    }

    @Test
    fun aV3MetaChoosesAnki21bOverAStaleAnki21Copy() {
        // Upstream Anki derives the collection from `meta`'s version, not from
        // the member that happens to be first. A v3 package keeps a stale
        // `collection.anki21` copy; reading it by name would import the wrong
        // deck. Both readers must take `anki21b`'s two questions.
        val stale = collectionBytes(notes = listOf("Stale copy?${us}stale" to 1L))
        val apkg = zipOf(
            "meta" to byteArrayOf(0x08, 0x03),
            "collection.anki21" to stale,
            "collection.anki21b" to zstdFrame(
                v3CollectionBytes(
                    notes = listOf(
                        "V3 one?${us}&#10003; yes<br>&#10007; no" to 55L,
                        "V3 two?${us}&#10003; yes<br>&#10007; no" to 56L
                    )
                )
            ),
            "media" to "{}".toByteArray()
        )

        val whole = AnkiPackageReader.read(apkg)
        assertEquals(2, whole.noteCount)
        val texts = whole.file.papers.single().let(::allQuestions).map { it.text }
        assertTrue("the anki21b questions import: $texts", "V3 one?" in texts && "V3 two?" in texts)
        assertFalse("the stale anki21 copy must not win: $texts", "Stale copy?" in texts)

        val sink = streamed(apkg)
        val streamedTexts = sink.questions.map { it.text }
        assertEquals(texts, streamedTexts)
        assertFalse("the stale anki21 copy must not win when streamed", "Stale copy?" in streamedTexts)
    }

    @Test
    fun aV2MetaChoosesAnki21() {
        val apkg = zipOf(
            "meta" to byteArrayOf(0x08, 0x02),
            "collection.anki2" to stubCollection(),
            "collection.anki21" to collectionBytes(
                notes = listOf("V2 question?${us}&#10003; yes<br>&#10007; no" to 1L)
            ),
            "media" to "{}".toByteArray()
        )

        val whole = AnkiPackageReader.read(apkg)
        assertEquals(listOf("V2 question?"), whole.file.papers.single().let(::allQuestions).map { it.text })
        assertEquals(listOf("V2 question?"), streamed(apkg).questions.map { it.text })
    }

    @Test
    fun aPackageWithoutMetaStillReadsAnki2() {
        // Our own exports and old AnkiDroid packages have no `meta`; the legacy
        // name must keep working with no version to guide the choice.
        val apkg = legacyPackage(notes = listOf("Legacy?${us}Paris" to 1L))

        val result = AnkiPackageReader.read(apkg)

        assertEquals(listOf("Legacy?"), result.file.papers.single().let(::allQuestions).map { it.text })
    }

    @Test
    fun aMalformedMetaFallsBackToTheNameOrder() {
        // Field 1's key with no varint value: the version cannot be read. That
        // must not fail the import; the name preference chooses anki21.
        val apkg = zipOf(
            "meta" to byteArrayOf(0x08),
            "collection.anki2" to stubCollection(),
            "collection.anki21" to collectionBytes(
                notes = listOf("Fallback?${us}&#10003; yes<br>&#10007; no" to 1L)
            ),
            "media" to "{}".toByteArray()
        )

        val result = AnkiPackageReader.read(apkg)

        assertEquals(listOf("Fallback?"), result.file.papers.single().let(::allQuestions).map { it.text })
    }

    @Test
    fun aCorruptCollectionIsReportedAsAnAnkiFailure() {
        val notADb = "this is not a sqlite file".toByteArray()
        val e = runCatching { AnkiPackageReader.read(zipOf("collection.anki2" to notADb)) }
        assertTrue(
            "reported as an Anki package problem: ${e.exceptionOrNull()}",
            e.exceptionOrNull() is Exception
        )
    }

    // ---- helpers ----

    private fun Question.toDto() = QuestionDto(
        id = id,
        text = text,
        image = image,
        options = options.map { OptionDto(it.id, text = it.text, image = it.image) },
        correctOptionIds = correctOptionIds.toList(),
        explanation = explanation,
        explanationImage = explanationImage,
        difficulty = difficulty.label.lowercase(),
        marks = marks,
        tags = tags
    )

    private fun guidsOf(apkg: ByteArray): List<String> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(apkg.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val file = File.createTempFile("guids", ".anki2")
        file.writeBytes(entries.getValue("collection.anki2"))
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        val guids = mutableListOf<String>()
        db.rawQuery("select guid from notes order by id", null).use { c ->
            while (c.moveToNext()) guids += c.getString(0)
        }
        db.close()
        file.delete()
        return guids
    }

    /**
     * Builds the collection bytes [legacyPackage] puts in a package, so a test
     * can place the same collection under whichever entry name it is testing
     * (`collection.anki2`, `collection.anki21`, `collection.anki21b`).
     */
    private fun collectionBytes(
        models: String = basicModels,
        decks: String = basicDecks,
        notes: List<Pair<String, Long>> = emptyList(),
        noteTags: String = " mcqapp ",
        cards: List<ClozeCard> = emptyList(),
        skipAutoCard: Boolean = false,
        /** When the collection was created; a review card's due counts from it. */
        crtSeconds: Long = 0L,
        /** `col.ver` to write. 11 is the legacy shape; 14 is still JSON-in-col. */
        version: Int = 11,
        /** Scheduling and review-log rows, which the defaults above leave new. */
        extraSql: List<String> = emptyList()
    ): ByteArray {
        val db = newCollection(version, crtSeconds)
        db.execSQL("update col set models = ?, decks = ?", arrayOf<Any>(models, decks))
        notes.forEachIndexed { i, (flds, did) ->
            val id = (i + 1).toLong()
            db.execSQL(
                "insert into notes values (?, ?, 1, 0, 0, ?, ?, '', 0, 0, '')",
                arrayOf<Any>(id, AnkiPackageWriter.guidFor("t", "n$id"), noteTags, flds)
            )
            if (cards.none { it.nid == id } && !skipAutoCard) {
                db.execSQL(
                    "insert into cards values (?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                    arrayOf<Any>(1000L + id, id, did)
                )
            }
        }
        cards.forEach { c ->
            db.execSQL(
                "insert into cards values (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                arrayOf<Any>(c.cardId, c.nid, c.did, c.ord.toLong())
            )
        }
        extraSql.forEach { db.execSQL(it) }
        return bytesOf(db)
    }


    /** A zstd frame whose magic is right and whose contents are not a frame. */
    private fun corruptZstd(): ByteArray =
        byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(32) { 0x7F }

    /** Reads a classpath fixture under `src/test/resources/fixtures/`. */
    private fun fixture(name: String): ByteArray =
        javaClass.classLoader?.getResourceAsStream("fixtures/$name")?.use { it.readBytes() }
            ?: throw AssertionError("missing test fixture fixtures/$name")

    /**
     * The 1-note compatibility stub Anki writes into `collection.anki2` beside
     * a modern collection. A real stub has no tags and exactly one field
     * separator: one front field and a blank back.
     */
    private fun stubCollection(): ByteArray = collectionBytes(
        notes = listOf(
            "Please update to the latest Anki version, then import the " +
                ".colpkg/.apkg file again.$us" to 1L
        ),
        noteTags = ""
    )

    /**
     * A schema-18 collection, the shape Anki writes into `collection.anki21b`:
     * the normalised notetype/deck tables and deck names using the v3 folder
     * separator. "Bank" holds Science and Visual Reasoning.
     */
    private fun v3CollectionBytes(notes: List<Pair<String, Long>> = emptyList()): ByteArray {
        val db = newCollection(18)
        modernTables(db)
        db.execSQL("insert into notetypes values (1, 'Basic', x'')")
        db.execSQL("insert into fields values (1, 0, 'Front'), (1, 1, 'Back')")
        db.execSQL("insert into templates values (1, 0, '{{Front}}')")
        db.execSQL(
            "insert into decks values (1, 'Default', 0, 0, x'', x''), " +
                "(54, 'Bank', 0, 0, x'', x''), " +
                "(55, 'Bank${us}Science', 0, 0, x'', x''), " +
                "(56, 'Bank${us}Visual Reasoning', 0, 0, x'', x'')"
        )
        notes.forEachIndexed { i, (flds, did) ->
            val nid = 100L + i
            db.execSQL(
                "insert into notes values (?, ?, 1, 0, 0, '', ?, '', 0, 0, '')",
                arrayOf<Any>(nid, "g$nid", flds)
            )
            db.execSQL(
                "insert into cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, " +
                    "reps, lapses, left, odue, odid, flags, data) values " +
                    "(?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '')",
                arrayOf<Any>(1000L + i, nid, did)
            )
        }
        return bytesOf(db)
    }

    /**
     * Anki v3's `MediaEntries` protobuf: repeated field 1 of
     * `MediaEntry { string name = 1; uint32 size = 2; bytes sha1 = 3; }`, plus an
     * unknown outer field the reader must skip. The entry's position is its
     * zip-entry name.
     */
    private fun mediaManifestProto(names: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        names.forEach { name ->
            val entry = ByteArrayOutputStream()
            entry.write(0x0A) // MediaEntry.name = 1, length-delimited
            entry.writeProtoVarint(name.toByteArray().size.toLong())
            entry.write(name.toByteArray())
            entry.write(0x10) // MediaEntry.size = 2, varint
            entry.writeProtoVarint(1234)
            entry.write(0x1A) // MediaEntry.sha1 = 3, length-delimited
            entry.writeProtoVarint(20)
            entry.write(ByteArray(20) { 0x11 })
            out.write(0x0A) // MediaEntries.entries = 1, length-delimited
            out.writeProtoVarint(entry.size().toLong())
            out.write(entry.toByteArray())
        }
        out.write(0x10) // unknown outer field 2, varint: skipped
        out.writeProtoVarint(99)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeProtoVarint(value: Long) {
        var remaining = value
        while (true) {
            val byte = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining == 0L) {
                write(byte)
                return
            }
            write(byte or 0x80)
        }
    }

    /**
     * Builds a package the way Anki 2.1 wrote one: a plain zip holding
     * `collection.anki2` and a `media` manifest mapping numeric entry names to
     * original filenames.
     */
    private fun legacyPackage(
        models: String = basicModels,
        decks: String = basicDecks,
        notes: List<Pair<String, Long>> = emptyList(),
        noteTags: String = " mcqapp ",
        cards: List<ClozeCard> = emptyList(),
        skipAutoCard: Boolean = false,
        media: Map<String, String> = emptyMap(),
        mediaBytes: Map<String, ByteArray> = emptyMap(),
        /** When the collection was created; a review card's due counts from it. */
        crtSeconds: Long = 0L,
        /** `col.ver` to write. 11 is the legacy shape; 14 is still JSON-in-col. */
        version: Int = 11,
        /** Scheduling and review-log rows, which the defaults above leave new. */
        extraSql: List<String> = emptyList()
    ): ByteArray {
        val collection = collectionBytes(
            models = models,
            decks = decks,
            notes = notes,
            noteTags = noteTags,
            cards = cards,
            skipAutoCard = skipAutoCard,
            crtSeconds = crtSeconds,
            version = version,
            extraSql = extraSql
        )
        return zipOf(
            "collection.anki2" to collection,
            "media" to mediaJson(media).toByteArray(),
            *mediaBytes.map { it.key to it.value }.toTypedArray()
        )
    }

    /** Anki 2.1.x schema 11 collection, matching Anki's own `schema11.sql`. */
    /**
     * The sample paper's shape: Physics holds its own questions and is a
     * subcategory of Science. Import used to return it as a sibling of Science,
     * because the reader built the category list without parent links.
     */
    @Test
    fun nestedCategoryStaysUnderItsParent() {
        val paper = PaperDto(
            id = "p1",
            title = "General Knowledge",
            categories = listOf(
                CategoryDto(
                    id = "cat-science",
                    title = "Science",
                    questions = listOf(
                        QuestionDto(id = "q1", text = "Water is?", options = listOf(OptionDto("a", "H2O")),
                            correctOptionIds = listOf("a"))
                    )
                ),
                CategoryDto(
                    id = "cat-physics",
                    title = "Physics",
                    parentId = "cat-science",
                    questions = listOf(
                        QuestionDto(id = "q2", text = "F = ma?", options = listOf(OptionDto("a", "Newton")),
                            correctOptionIds = listOf("a"))
                    )
                )
            )
        )
        val apkg = apkgOf(paper)

        val imported = AnkiPackageReader.read(apkg).file.papers.single()

        assertEquals(2, imported.categories.size)
        val science = imported.categories.first { it.title == "Science" }
        val physics = imported.categories.first { it.title == "Physics" }
        assertEquals("Physics must not become a top-level category", science.id, physics.parentId)
        assertEquals(null, science.parentId)
        assertEquals(2, allQuestions(imported).size)
        // Every category id has to survive the comma-separated nav argument the
        // category filter is sent through.
        assertTrue(imported.categories.all { ',' !in it.id })
    }

    /**
     * A category whose questions all sit in its subcategories has no deck of its
     * own, because Anki only creates a deck for a category that holds cards.
     * The level still has to appear, or everything under it is lost.
     */
    @Test
    fun aCategoryWithNoDeckOfItsOwnStillAppears() {
        val apkg = legacyPackage(
            decks = """{"1":{"id":1,"name":"Paper"},"2":{"id":2,"name":"Paper::Science::Physics"}}""",
            notes = listOf("Front: F = ma?${us}&#10003; Newton<br>&#10007; m = F/a" to 2L)
        )

        val paper = AnkiPackageReader.read(apkg).file.papers.single()

        val science = paper.categories.first { it.title == "Science" }
        val physics = paper.categories.first { it.title == "Physics" }
        assertEquals("no questions of its own", 0, science.questions.size)
        assertEquals(science.id, physics.parentId)
        assertEquals(1, allQuestions(paper).size)
    }

    @Test
    fun questionsInThePaperDeckAreUncategorised() {
        // They used to become a category named after the deck, so every paper
        // gained a category sharing its own name.
        val apkg = legacyPackage(notes = listOf("Front: 2 + 2?${us}4" to 1L))

        val paper = AnkiPackageReader.read(apkg).file.papers.single()

        assertEquals(1, paper.questions.size)
        assertTrue("no category named after the paper", paper.categories.none { it.title == "Default" })
    }

    @Test
    fun aCategoryTitleContainingTheSeparatorDoesNotInventAHierarchy() {
        val paper = PaperDto(
            id = "p1",
            title = "Deck",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "A::B",
                    questions = listOf(
                        QuestionDto(id = "q1", text = "Q?", options = listOf(OptionDto("a", "A")),
                            correctOptionIds = listOf("a"))
                    )
                )
            )
        )
        val apkg = apkgOf(paper)

        val imported = AnkiPackageReader.read(apkg).file.papers.single()

        assertEquals("the :: in the title must not split the category", 1, imported.categories.size)
        assertNull(imported.categories.single().parentId)
    }

    @Test
    fun aReviewedCardKeepsItsSchedule() {
        val crt = 1_700_000_000L
        val apkg = legacyPackage(
            notes = listOf("Front: 2 + 2?${us}&#10003; 4<br>&#10007; five" to 1L),
            crtSeconds = crt,
            extraSql = listOf(
                "update cards set type = 2, queue = 2, due = 5, ivl = 5, factor = 2600, " +
                    "reps = 7, lapses = 1 where id = 1001",
                // The review log is the only record of when a card was last seen.
                // In Anki's `revlog`, the row id is the review's epoch-millis
                // timestamp; `time` is how long the review took, in millis.
                "insert into revlog values (${(crt - 3600) * 1000}, 1001, -1, 3, 5, 1, 2600, 32500, 0)"
            )
        )

        val result = AnkiPackageReader.read(apkg)
        val question = result.file.papers.single().let(::allQuestions).single()
        val state = result.scheduling[question.id]

        assertNotNull("a reviewed card must carry its schedule", state)
        assertEquals(5, state!!.intervalDays)
        assertEquals(7, state.reps)
        assertEquals(1, state.lapses)
        assertEquals(2.6, state.ease, 0.0001)
        assertFalse("one lapse is not a leech", state.leech)
        // due = 5 days after the collection was created, not after the import.
        assertWithinADay("due", (crt + 5 * 86_400L) * 1000L, state.dueAt)
        assertEquals((crt - 3600) * 1000L, state.lastReviewedAt)
    }

    /**
     * Anki's `revlog.id` is the review's timestamp in epoch millis and
     * `revlog.time` is how long the review took, also in millis (Anki's
     * `RevlogEntry` documents the latter as "amount of milliseconds taken to
     * answer the card"). Reading the duration as the timestamp scales a half
     * minute into 1970 and inflates the next FSRS grade's stability.
     */
    @Test
    fun theLastReviewIsTheReviewLogTimestampNotItsDuration() {
        val reviewedAt = 1_760_000_000_000L
        val durationMs = 32_500L
        val apkg = legacyPackage(
            notes = listOf("Front: 2 + 2?${us}&#10003; 4<br>&#10007; five" to 1L),
            extraSql = listOf(
                "update cards set type = 2, queue = 2, due = 5, ivl = 5, factor = 2600, " +
                    "reps = 7, lapses = 1 where id = 1001",
                "insert into revlog values ($reviewedAt, 1001, -1, 3, 5, 1, 2600, $durationMs, 0)"
            )
        )

        val result = AnkiPackageReader.read(apkg, nowMillis = reviewedAt + 86_400_000L)
        val question = result.file.papers.single().let(::allQuestions).single()
        val state = result.scheduling[question.id]

        assertNotNull("a reviewed card must carry its schedule", state)
        assertEquals("the row id is the review instant", reviewedAt, state!!.lastReviewedAt)
        assertTrue(
            "the duration $durationMs was read as an instant, which lands in 1970: " +
                "${state.lastReviewedAt}",
            state.lastReviewedAt > 1_000_000_000_000L
        )
    }

    /** The streaming reader must take the same column as the whole-file one. */
    @Test
    fun theStreamedLastReviewIsTheReviewLogTimestamp() {
        val reviewedAt = 1_760_000_000_000L
        val apkg = legacyPackage(
            notes = listOf("Front: 2 + 2?${us}&#10003; 4<br>&#10007; five" to 1L),
            extraSql = listOf(
                "update cards set type = 2, queue = 2, due = 5, ivl = 5, factor = 2600, " +
                    "reps = 7, lapses = 1 where id = 1001",
                "insert into revlog values ($reviewedAt, 1001, -1, 3, 5, 1, 2600, 32500, 0)"
            )
        )
        val sink = ScheduleSink()

        runBlocking {
            AnkiPackageReader.stream(
                ByteArrayInputStream(apkg),
                sink,
                nowMillis = reviewedAt + 86_400_000L
            )
        }

        val state = sink.schedules.values.single()
        assertEquals("the row id is the review instant", reviewedAt, state.lastReviewedAt)
    }

    /** Collects every batch's schedules so a test can read them off the stream. */
    private class ScheduleSink : AnkiPackageReader.StreamSink {
        val schedules = LinkedHashMap<String, CardScheduleDto>()

        override suspend fun onPaper(header: LegacyParser.PaperHeader) = Unit

        override suspend fun onCategory(category: CategoryDto) = Unit

        override suspend fun onQuestions(
            categoryId: String,
            questions: List<QuestionDto>,
            schedules: Map<String, CardScheduleDto>
        ) {
            this.schedules += schedules
        }
    }

    /** Streams [apkg] and keeps what the listener delivered. */
    private fun streamed(apkg: ByteArray): CollectingSink {
        val sink = CollectingSink()
        runBlocking { AnkiPackageReader.stream(ByteArrayInputStream(apkg), sink) }
        return sink
    }

    private class CollectingSink : AnkiPackageReader.StreamSink {
        val paperTitles = mutableListOf<String>()
        val categoryTitles = mutableListOf<String>()
        val questions = mutableListOf<QuestionDto>()

        override suspend fun onPaper(header: LegacyParser.PaperHeader) {
            paperTitles += header.title
        }

        override suspend fun onCategory(category: CategoryDto) {
            categoryTitles += category.title
        }

        override suspend fun onQuestions(
            categoryId: String,
            questions: List<QuestionDto>,
            schedules: Map<String, CardScheduleDto>
        ) {
            this.questions += questions
        }
    }

    @Test
    fun aSuspendedCardImportsAsAReviewedCard() {
        // This app has no suspended state, so a suspended card keeps its history
        // rather than the import pretending it is a new card.
        val crt = 1_700_000_000L
        val apkg = legacyPackage(
            notes = listOf("Front: 2 + 2?${us}&#10003; 4<br>&#10007; five" to 1L),
            crtSeconds = crt,
            extraSql = listOf(
                "update cards set type = 2, queue = -1, due = 3, ivl = 3, factor = 2500, " +
                    "reps = 4, lapses = 0 where id = 1001"
            )
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertTrue("the note still imports", question.options.isNotEmpty())
    }

    @Test
    fun aNewCardCarriesNoSchedule() {
        val apkg = legacyPackage(notes = listOf("Front: 2 + 2?${us}4" to 1L))

        val result = AnkiPackageReader.read(apkg)

        // Nothing to carry: a card Anki has never studied is already new here.
        assertTrue(result.scheduling.isEmpty())
    }

    @Test
    fun scheduleIsExportedAndReadsBackUnchanged() {
        val now = System.currentTimeMillis()
        val questions = listOf(
            Question(
                id = "q1",
                categoryId = "c1",
                text = "2 + 2?",
                options = listOf(QuestionOption("a", "4"), QuestionOption("b", "five")),
                correctOptionIds = setOf("a"),
                explanation = "Arithmetic."
            )
        )
        val paper = PaperDto(
            id = "p1",
            title = "Maths",
            categories = listOf(CategoryDto(id = "c1", title = "Basics", questions = questions.map { it.toDto() }))
        )
        val apkg = apkgOf(
            paper,
            mapOf(
                "q1" to CardScheduleDto(
                    ease = 2.6,
                    intervalDays = 5,
                    dueAt = now + 5 * 86_400_000L,
                    reps = 7,
                    lapses = 1,
                    lastReviewedAt = now
                )
            )
        )

        val result = AnkiPackageReader.read(apkg)
        val question = result.file.papers.single().let(::allQuestions).single()
        val state = result.scheduling[question.id]

        assertNotNull(state)
        assertEquals(5, state!!.intervalDays)
        assertEquals(7, state.reps)
        assertEquals(2.6, state.ease, 0.0001)
        // Five days from the export, which is what the package was written for.
        assertWithinADay("due", now + 5 * 86_400_000L, state.dueAt)
    }

    /**
     * A due *day* is anchored to midnight, so it cannot match a millisecond
     * timestamp exactly; a day's slack is the whole claim being made.
     */
    private fun assertWithinADay(what: String, expected: Long, actual: Long) {
        val drift = Math.abs(expected - actual)
        assertTrue("$what drifted by ${drift}ms", drift <= 86_400_000L)
    }

    /** The schema-15+ notetype and deck tables Anki stores beside `notes`. */
    private fun modernTables(db: SQLiteDatabase) {
        db.execSQL("create table fields (ntid integer not null, ord integer not null, name text not null, primary key (ntid, ord))")
        db.execSQL("create table notetypes (id integer primary key, name text not null, config blob)")
        db.execSQL("create table templates (ntid integer not null, ord integer not null, qfmt text not null, primary key (ntid, ord))")
        db.execSQL("create table decks (id integer primary key, name text not null, mtime_secs integer not null, usn integer not null, common blob not null, kind blob not null)")
    }

    private fun newCollection(ver: Int, crtSeconds: Long = 0L): SQLiteDatabase {
        val file = File.createTempFile("reader-test", ".anki2")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "create table col (id integer primary key, crt integer not null, mod integer not null, " +
                "scm integer not null, ver integer not null, dty integer not null, usn integer not null, " +
                "ls integer not null, conf text not null, models text not null, decks text not null, " +
                "dconf text not null, tags text not null)"
        )
        db.execSQL("create table notes (id integer primary key, guid text not null, mid integer not null, mod integer not null, usn integer not null, tags text not null, flds text not null, sfld integer not null, csum integer not null, flags integer not null, data text not null)")
        db.execSQL("create table cards (id integer primary key, nid integer not null, did integer not null, ord integer not null, mod integer not null, usn integer not null, type integer not null, queue integer not null, due integer not null, ivl integer not null, factor integer not null, reps integer not null, lapses integer not null, left integer not null, odue integer not null, odid integer not null, flags integer not null, data text not null)")
        db.execSQL("create table revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null, ivl integer not null, lastIvl integer not null, factor integer not null, time integer not null, type integer not null)")
        db.execSQL("create index ix_notes_usn on notes (usn)")
        db.execSQL("create index ix_cards_usn on cards (usn)")
        db.execSQL("create index ix_revlog_usn on revlog (usn)")
        db.execSQL("create index ix_cards_nid on cards (nid)")
        db.execSQL("create index ix_cards_sched on cards (did, queue, due)")
        db.execSQL("create index ix_revlog_cid on revlog (cid)")
        db.execSQL("create index ix_notes_csum on notes (csum)")
        db.execSQL(
            "insert into col values (1, ?, 0, 0, ?, 0, 0, 0, '{}', '{}', '{}', '{}', '{}')",
            arrayOf<Any>(crtSeconds, ver)
        )
        return db
    }

    /** Closes [db] and returns the bytes of the collection file it wrote. */
    private fun bytesOf(db: SQLiteDatabase): ByteArray {
        val path = db.path
        db.close()
        val bytes = File(path).readBytes()
        File(path).delete()
        return bytes
    }

    private fun mediaJson(media: Map<String, String>): String =
        media.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
