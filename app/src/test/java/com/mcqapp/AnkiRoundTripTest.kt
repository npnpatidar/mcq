package com.mcqapp

import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.Passage
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The app's own export must be readable by its own import, which is the whole
 * point of the schema-11 choice: Anki upgrades a legacy package on import, and
 * we read back both what we wrote and what Anki left after that upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnkiRoundTripTest {

    @Test
    fun exportedDeckReadsBackAsTheSameQuestion() {
        val paper = PaperDto(
            id = "p1",
            title = "Biology",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Cells",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "Which organelle makes ATP?",
                            image = "data:image/png;base64,iVBORw0KGgo=",
                            options = listOf(
                                OptionDto("a", "Mitochondrion"),
                                OptionDto("b", "Ribosome"),
                                OptionDto("c", "Nucleus")
                            ),
                            correctOptionIds = listOf("a"),
                            explanation = "ATP synthase sits on the inner membrane.",
                            difficulty = "medium",
                            marks = 2.0,
                            tags = listOf("biology")
                        )
                    )
                )
            )
        )

        val apkg = apkgOf(paper)
        val read = AnkiPackageReader.read(apkg)

        assertEquals(0, read.recallCount)
        assertEquals(1, read.file.papers.size)
        val readPaper = read.file.papers.single()
        assertEquals("Biology", readPaper.title)
        val category = readPaper.categories.single()
        assertEquals("Cells", category.title)
        val q = category.questions.single()
        assertEquals(paper.categories.single().questions.single().text, q.text)
        assertEquals(listOf("Mitochondrion", "Ribosome", "Nucleus"), q.options.map { it.text })
        assertEquals(1, q.correctOptionIds.size)
        assertEquals("Mitochondrion", q.options.single { it.id == q.correctOptionIds.single() }.text)
        assertEquals("ATP synthase sits on the inner membrane.", q.explanation)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", q.image)
    }

    @Test
    fun passageMembersRoundTripWithTheirSharedContext() {
        val paper = PaperDto(
            id = "p1",
            title = "Reading",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Cells",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "Which organelle makes ATP?",
                            options = listOf(OptionDto("a", "Mitochondrion"), OptionDto("b", "Ribosome")),
                            correctOptionIds = listOf("a"),
                            explanation = "ATP synthase.",
                            passageId = "passage-1"
                        ),
                        QuestionDto(
                            id = "q2",
                            text = "Where does glycolysis happen?",
                            options = listOf(OptionDto("a", "Cytosol"), OptionDto("b", "Nucleus")),
                            correctOptionIds = listOf("a"),
                            explanation = "Cytoplasm.",
                            passageId = "passage-1"
                        )
                    )
                )
            )
        )
        val passages = mapOf(
            "passage-1" to Passage(
                id = "passage-1",
                categoryId = "c1",
                title = "Energy",
                elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Cells make ATP."))
            )
        )

        val apkg = apkgOf(paper, passages = passages)
        val read = AnkiPackageReader.read(apkg)

        // The reader rebuilds the passage block from the payload blob, so the
        // round trip restores the grouping without a database lookup.
        val passage = read.file.passages.single()
        assertEquals("passage-1", passage.id)
        assertEquals("Energy", passage.title)
        assertEquals("Cells make ATP.", passage.elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.TextElement>()
            .joinToString("") { it.text })
        val questions = read.file.papers.single().categories.single().questions
        assertEquals(listOf("passage-1", "passage-1"), questions.map { it.passageId })
        // The passage sits on the front of every member's card, above the stem.
        val fronts = collectionFields(apkg).map { it.substringBefore("\u001f") }
        assertEquals(2, fronts.size)
        assertTrue(fronts.all { it.contains("Energy") && it.contains("Cells make ATP.") })
    }

    private fun collectionFields(apkg: ByteArray): List<String> {
        val collection = ZipInputStream(apkg.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != "collection.anki2") entry = zip.nextEntry
            entry?.let { zip.readBytes() } ?: throw AssertionError("no collection.anki2")
        }
        val file = File.createTempFile("apkg-roundtrip", ".anki2")
        file.writeBytes(collection)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val fields = mutableListOf<String>()
            db.rawQuery("select flds from notes order by id", null).use { c ->
                while (c.moveToNext()) fields += c.getString(0)
            }
            return fields
        } finally {
            db.close()
            file.delete()
        }
    }

    /**
     * A stem that is only a picture exists fine in Anki (field-0 is nothing
     * but the `<img>`); the reader used to blank-strip it to "" and return
     * null, so the note VANISHED from the deck with no counter and no warning,
     * and the writer's sfld/csum disagreed with the front it indexed. The
     * reader keeps the note — the image travels as `question.image` — and the
     * writer's sfld carries the stem's img form. The completely-empty stem
     * (neither text nor image) is still dropped, now counted and warned.
     */
    @Test
    fun anImageOnlyStemIsNotVanishedOnRoundTrip() {
        val imgSrc = "data:image/png;base64,iVBORw0KGgo="
        val paper = PaperDto(
            id = "p1",
            title = "Diagrams",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Figures",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "",
                            image = imgSrc,
                            options = listOf(OptionDto("a", "Yes"), OptionDto("b", "No")),
                            correctOptionIds = listOf("a"),
                            explanation = "The diagram shows it."
                        )
                    )
                )
            )
        )
        val apkg = apkgOf(paper)
        val read = AnkiPackageReader.read(apkg)
        val q = read.file.papers.single().categories.single().questions.single()
        assertEquals("", q.text)
        assertEquals(imgSrc, q.image)
        // The card front keeps the picture for Anki's own display/template.
        val front = collectionFields(apkg).single().substringBefore("\u001f")
        assertTrue("front must carry the <img>", front.contains("<img"))

        // The sort field is the stem's img-included form, not "" (whose blank
        // csum disagreed with the front it indexed for the same shape).
        val sfld = collectionSortFields(apkg).single()
        assertTrue("sfld must name the image, got \"$sfld\"", sfld.contains("<img"))
    }

    /**
     * A payload that omits the `image` key (older or hand-edited one) used to
     * fall back to the first `<img>` on the question half of the front — and
     * splitFront's "question half" keeps the passage block, whose picture was
     * embedded above the stem. The passage was then also restored separately
     * with its own picture: duplicated under questions.image, the stem showed
     * it twice, and any branching on question.image != null misdirected.
     */
    @Test
    fun aPassagePictureIsNotAttributedToItsMemberQuestion() {
        val pngSrc =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val paper = PaperDto(
            id = "p1",
            title = "Reading",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Figures",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "As per the diagram, what is shown?",
                            options = listOf(OptionDto("a", "A cell"), OptionDto("b", "A rock")),
                            correctOptionIds = listOf("a"),
                            explanation = "Cells have organelles.",
                            passageId = "pass-1"
                        )
                    )
                )
            )
        )
        val passages = mapOf(
            "pass-1" to Passage(id = "pass-1", categoryId = "c1", title = "Figure")
            // NOTE: no elements either — only the image rides with the passage.
        )
        // The passage only carries a picture; build it through the writer with
        // an image-carrying Passage so buildFront embeds that <img> above the
        // stem, then read it back.
        val passageWithPicture = passages.getValue("pass-1").copy(image = pngSrc)

        val apkg = apkgOf(paper, passages = mapOf("pass-1" to passageWithPicture))
        val read = AnkiPackageReader.read(apkg)

        assertEquals("the member question's own image stays null (its payload has no image)",
            null, read.file.papers.single().categories.single().questions.single().image)
        assertEquals("the passage keeps its picture for its own block",
            pngSrc, read.file.passages.single().image)
    }

    @Test
    fun aCompletelyEmptyNoteIsStillDropped() {
        // text, image, options, explanation all empty: the front field arrives
        // empty and there is no image to restore — a note with nothing on it is
        // dropped (with the count/warning in the log), never a half-broken
        // question.
        val paper = PaperDto(
            id = "p1",
            title = "Empty",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Uh",
                    questions = listOf(
                        QuestionDto(id = "q-blank", text = "", options = emptyList())
                    )
                )
            )
        )
        val read = AnkiPackageReader.read(apkgOf(paper))
        assertTrue(read.file.papers.all { it.categories.all { c -> c.questions.isEmpty() } } ||
            read.file.papers.isEmpty())
        assertTrue("no question may survive a fully empty note",
            read.file.papers.flatMap { it.categories.flatMap { c -> c.questions } }.isEmpty())
    }

    private fun collectionSortFields(apkg: ByteArray): List<String> {
        val collection = ZipInputStream(apkg.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != "collection.anki2") entry = zip.nextEntry
            entry?.let { zip.readBytes() } ?: throw AssertionError("no collection.anki2")
        }
        val file = File.createTempFile("apkg-roundtrip-sfld", ".anki2")
        file.writeBytes(collection)
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val fields = mutableListOf<String>()
            db.rawQuery("select sfld from notes order by id", null).use { c ->
                while (c.moveToNext()) fields += c.getString(0)
            }
            return fields
        } finally {
            db.close()
            file.delete()
        }
    }
}
