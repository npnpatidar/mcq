package com.mcqapp

import com.mcqapp.data.anki.AnkiDtoMapper
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

        val apkg = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper))
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

        val apkg = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper), passages = passages)
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
}
