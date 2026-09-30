package com.mcqapp

import android.database.sqlite.SQLiteDatabase
import com.mcqapp.data.anki.AnkiDtoMapper
import com.mcqapp.data.anki.AnkiMediaPool
import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.Question
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Structural tests for the `.apkg` writer.
 *
 * Anki will only import a package whose `collection.anki2` matches its schema-11
 * layout and whose `col` row holds well-formed `models`/`decks`/`dconf` JSON, so
 * these tests unzip the produced package and inspect the real SQLite it wrote
 * rather than trusting the byte layout in the abstract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnkiPackageWriterTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val png1x1 =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private fun samplePaper() = PaperDto(
        id = "p1",
        title = "Biology: Cell",
        categories = listOf(
            CategoryDto(
                id = "c1",
                title = "Basics",
                questions = listOf(
                    QuestionDto(
                        id = "q1",
                        text = "Which organelle makes ATP?",
                        options = listOf(
                            OptionDto("o1", "Mitochondrion"),
                            OptionDto("o2", "Ribosome")
                        ),
                        correctOptionIds = listOf("o1"),
                        explanation = "It hosts the electron transport chain.",
                        difficulty = "easy",
                        tags = listOf("energy")
                    )
                )
            )
        )
    )

    private fun question(
        id: String,
        text: String,
        correct: List<String> = listOf("a"),
        options: List<OptionDto> = listOf(OptionDto("a", "A"), OptionDto("b", "B")),
        explanation: String = "",
        image: String? = null,
        explanationImage: String? = null
    ) = Question(
        id = id,
        categoryId = id,
        text = text,
        image = image,
        options = options.map { com.mcqapp.domain.QuestionOption(it.id, it.text, it.image) },
        correctOptionIds = correct.toSet(),
        explanation = explanation,
        explanationImage = explanationImage,
        difficulty = com.mcqapp.domain.Difficulty.MEDIUM,
        tags = emptyList()
    )

    private fun entries(apkg: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(apkg.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
            }
        }
        return out
    }

    /**
     * Schema 11 has no `decks` table: the deck names live in the `col.decks`
     * JSON blob, keyed by deck id.
     */
    private fun deckNames(db: SQLiteDatabase): Map<Long, String> {
        val out = HashMap<Long, String>()
        db.rawQuery("select decks from col", null).use { c ->
            assertTrue(c.moveToFirst())
            json.parseToJsonElement(c.getString(0)).jsonObject.forEach { (id, deck) ->
                out[id.toLong()] = deck.jsonObject["name"]!!.jsonPrimitive.content
            }
        }
        return out
    }

    private fun openCollection(bytes: ByteArray): Pair<SQLiteDatabase, File> {
        val file = File.createTempFile("apkg-test", ".anki2")
        file.writeBytes(bytes)
        return SQLiteDatabase.openOrCreateDatabase(file, null) to file
    }

    @Test
    fun writesLegacyCollectionAtZipRoot() {
        val paper = samplePaper()
        val apkg = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper))
        val entries = entries(apkg)

        assertTrue("collection.anki2 must be present", "collection.anki2" in entries)
        // No images in this paper, so no media manifest or numbered files.
        assertTrue("media absent without images", "media" !in entries)
        assertEquals(1, entries.size)
    }

    @Test
    fun collectionRowUsesSchemaElevenAndValidConfigBlobs() {
        val paper = samplePaper()
        val apkg = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper))
        val (db, file) = openCollection(entries(apkg)["collection.anki2"]!!)
        try {
            db.rawQuery("select ver, conf, models, decks, dconf, tags from col", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(11, c.getInt(0))
                val conf = json.parseToJsonElement(c.getString(1)).jsonObject
                assertEquals(2L, conf["nextPos"]!!.jsonPrimitive.long)
                val models = json.parseToJsonElement(c.getString(2)).jsonObject
                val model = models.values.first().jsonObject
                assertEquals("Basic", model["name"]!!.jsonPrimitive.content)
                assertEquals(0, model["type"]!!.jsonPrimitive.int)
                assertEquals(3, model["flds"]!!.jsonArray.size)
                assertEquals(
                    "mcqapp",
                    model["flds"]!!.jsonArray[2].jsonObject["name"]!!.jsonPrimitive.content
                )
                assertEquals(1, model["tmpls"]!!.jsonArray.size)
                // The paper is the root deck and its category a subdeck.
                val decks = json.parseToJsonElement(c.getString(3)).jsonObject
                assertEquals(
                    listOf("Biology: Cell", "Biology: Cell::Basics"),
                    decks.values.map { it.jsonObject["name"]!!.jsonPrimitive.content }
                )
                assertEquals(
                    "both decks active",
                    2,
                    json.parseToJsonElement(c.getString(1)).jsonObject["activeDecks"]!!.jsonArray.size
                )
                val dconf = json.parseToJsonElement(c.getString(4)).jsonObject
                val deckConfig = dconf.values.first().jsonObject
                assertEquals(20, deckConfig["new"]!!.jsonObject["perDay"]!!.jsonPrimitive.int)
                assertEquals(200, deckConfig["rev"]!!.jsonObject["perDay"]!!.jsonPrimitive.int)
                assertEquals(8, deckConfig["lapse"]!!.jsonObject["leechFails"]!!.jsonPrimitive.int)
                assertEquals("{}", c.getString(5))
            }
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun everyQuestionBecomesANoteWithFrontAndBackFields() {
        val paper = samplePaper()
        val apkg = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper))
        val (db, file) = openCollection(entries(apkg)["collection.anki2"]!!)
        try {
            db.rawQuery("select guid, tags, flds, sfld, csum from notes", null).use { n ->
                assertTrue(n.moveToFirst())
                val guid = n.getString(0)
                assertEquals("Anki guids are 10 chars", 10, guid.length)
                val tags = n.getString(1)
                assertTrue("difficulty tag: $tags", "mcqapp-difficulty-easy" in tags)
                assertTrue("user tag: $tags", " energy " in tags)
                val flds = n.getString(2)
                val front = flds.substringBefore("\u001f")
                val back = flds.substringAfter("\u001f").substringBefore("\u001f")
                assertTrue("question on the front: $front", "Which organelle makes ATP?" in front)
                assertTrue("option on the front: $front", "Mitochondrion" in front)
                assertTrue("option on the front: $front", "Ribosome" in front)
                assertTrue("answer on the back: $back", "&#10003; <b>A.</b> Mitochondrion" in back)
                assertTrue("explanation present: $back", "electron transport chain" in back)
                assertTrue(
                    "only the correct answer is revealed: $back",
                    !back.contains("Ribosome")
                )
                assertEquals("Which organelle makes ATP?", n.getString(3))
                val csum = n.getLong(4)
                assertTrue("checksum fits Anki's u32: $csum", csum > 0 && csum <= 0xFFFFFFFFL)
            }
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun multiCorrectQuestionsAreLabelledAndEveryNoteHasANewCard() {
        val paper = samplePaper()
        val questions = listOf(
            question("q1", "Pick two", correct = listOf("a", "b")),
            question("q2", "Pick one", correct = listOf("b"))
        )
        val apkg = AnkiPackageWriter.write(paper, questions)
        val (db, file) = openCollection(entries(apkg)["collection.anki2"]!!)
        try {
            db.rawQuery("select flds from notes order by id", null).use { n ->
                n.moveToFirst()
                assertTrue(
                    "multi-answer note is labelled",
                    "Select all that apply." in n.getString(0)
                )
            }
            db.rawQuery("select nid, ord, type, queue, due, ivl, factor, reps, lapses from cards order by due", null)
                .use { c ->
                    assertEquals(2, c.count)
                    var expectedNid = -1L
                    var position = 1
                    while (c.moveToNext()) {
                        val nid = c.getLong(0)
                        if (expectedNid >= 0) assertTrue("distinct notes", nid != expectedNid)
                        expectedNid = nid
                        assertEquals("ord 0 for Basic", 0, c.getInt(1))
                        assertEquals("new card type", 0, c.getInt(2))
                        assertEquals("new queue", 0, c.getInt(3))
                        assertEquals("due orders new cards", position++, c.getInt(4))
                        assertEquals(0, c.getInt(5))
                        assertEquals(0, c.getInt(6))
                        assertEquals(0, c.getInt(7))
                        assertEquals(0, c.getInt(8))
                    }
                }
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun embeddedImagesAreReferencedByFilenameNotByZipEntryName() {
        val paper = samplePaper()
        val questions = listOf(
            question("q1", "See diagram", image = png1x1),
            question("q2", "Second", image = png1x1),
            question("q3", "Explained", explanation = "why", explanationImage = png1x1)
        )
        val apkg = AnkiPackageWriter.write(paper, questions)
        val entries = entries(apkg)

        // A single decoded image shared by three fields is stored once.
        assertTrue("media manifest written", "media" in entries)
        assertTrue("numeric media entry written", "0" in entries)
        val manifest = json.parseToJsonElement(String(entries["media"]!!)).jsonObject
        assertEquals("mcqapp-0.png", manifest["0"]!!.jsonPrimitive.content)

        val (db, file) = openCollection(entries["collection.anki2"]!!)
        try {
            db.rawQuery("select flds from notes order by id", null).use { n ->
                n.moveToFirst()
                // Anki keys its media map by filename and rewrites `src` from the
                // field text, so a numeric reference here resolves to nothing.
                assertTrue(
                    "front references the filename: ${n.getString(0)}",
                    "<img src=\"mcqapp-0.png\">" in n.getString(0)
                )
                assertTrue("no numeric reference", "<img src=\"0\">" !in n.getString(0))
            }
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun aCardsDeckMatchesItsCategoriesSubdeck() {
        val paper = samplePaper()
        val apkg = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper))
        val (db, file) = openCollection(entries(apkg)["collection.anki2"]!!)
        try {
            val names = deckNames(db)
            db.rawQuery("select did from cards order by due", null).use { c ->
                assertTrue(c.moveToFirst())
                // samplePaper puts its only question in the "Basics" category.
                assertEquals("Biology: Cell::Basics", names[c.getLong(0)])
            }
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun anUncategorisedQuestionGoesInThePaperDeck() {
        val paper = PaperDto(id = "p", title = "Flat", questions = listOf(QuestionDto(id = "q1", text = "One")))
        val apkg = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper))
        val (db, file) = openCollection(entries(apkg)["collection.anki2"]!!)
        try {
            val names = deckNames(db)
            db.rawQuery("select did from cards", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Flat", names[c.getLong(0)])
            }
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test
    fun questionTextIsHtmlEscaped() {
        val pool = AnkiMediaPool()
        val html = pool.htmlField("5 < 6 & \"quoted\"", null)
        assertEquals("5 &lt; 6 &amp; &quot;quoted&quot;", html)
    }

    @Test
    fun flattensCategoriesDepthFirstThenTopLevelQuestions() {
        val paper = PaperDto(
            id = "p",
            title = "T",
            categories = listOf(
                CategoryDto(
                    id = "root",
                    title = "Root",
                    questions = listOf(QuestionDto(id = "q1", text = "one")),
                    // a child of root
                ),
                CategoryDto(
                    id = "child",
                    title = "Child",
                    parentId = "root",
                    questions = listOf(QuestionDto(id = "q2", text = "two"))
                )
            ),
            questions = listOf(QuestionDto(id = "q3", text = "three"))
        )
        val ids = AnkiDtoMapper.flattenQuestions(paper).map { it.id }
        assertEquals(listOf("q1", "q2", "q3"), ids)
    }

    @Test
    fun guidsAreStableForTheSameQuestionButDifferBetweenQuestions() {
        assertEquals(AnkiPackageWriter.guidFor("p1", "q1"), AnkiPackageWriter.guidFor("p1", "q1"))
        assertEquals(10, AnkiPackageWriter.guidFor("p1", "q1").length)
        assertTrue(
            AnkiPackageWriter.guidFor("p1", "q1") != AnkiPackageWriter.guidFor("p1", "q2")
        )
        assertTrue(
            AnkiPackageWriter.guidFor("p1", "q1") != AnkiPackageWriter.guidFor("p2", "q1")
        )
    }
}