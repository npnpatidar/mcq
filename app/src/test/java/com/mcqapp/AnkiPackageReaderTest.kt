package com.mcqapp

import android.database.sqlite.SQLiteDatabase
import com.mcqapp.data.anki.AnkiDtoMapper
import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Reading tests for `.apkg` import.
 *
 * Anki packages come in two shapes and the reader has to accept both: a legacy
 * zip (schema 11, `col.models`/`col.decks` JSON) and the modern one (schema 14+
 * with normalised notetype and deck tables). The foreign-package tests build
 * those databases by hand so they exercise Anki's actual layout rather than our
 * own writer's output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnkiPackageReaderTest {

    private val png1x1 =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    private val pngBytes = java.util.Base64.getDecoder().decode(png1x1.substringAfterLast(","))

    private val us = AnkiPackageWriter.FIELD_SEPARATOR

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
        val apkg = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper))

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

        val first = guidsOf(AnkiPackageWriter.write(paper, questions))
        val second = guidsOf(AnkiPackageWriter.write(paper, questions))

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
        val apkg = AnkiPackageWriter.write(paper, AnkiDtoMapper.flattenQuestions(paper))

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
    fun aNoteWithNoMarkersBecomesASingleOptionRecallQuestion() {
        val apkg = legacyPackage(notes = listOf("Capital of France?${us}Paris${us}" to 1L))

        val result = AnkiPackageReader.read(apkg)
        val question = result.file.papers.single().let(::allQuestions).single()

        assertEquals("Capital of France?", question.text)
        assertEquals(listOf("Paris"), question.options.map { it.text })
        assertEquals(listOf("o0"), question.correctOptionIds)
        assertEquals(1, result.recallCount)
    }

    @Test
    fun htmlInFieldsIsFlattenedToPlainText() {
        val apkg = legacyPackage(
            notes = listOf("<b>Capital</b> of<br>France?${us}<div>Paris</div><div>Marseille</div>" to 1L)
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals("Capital of\nFrance?", question.text)
        // With nothing to say which line is the answer, the whole answer is kept
        // as one option rather than guessing.
        assertEquals(listOf("Paris\nMarseille"), question.options.map { it.text })
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
        assertEquals(listOf("Biology", "Cells"), paper.categories.map { it.title })
        assertEquals(listOf("top level", "nested"), paper.categories.map { it.questions.single().text })
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
    fun imagesReferencedByZipEntryNameAreInlined() {
        val apkg = legacyPackage(
            notes = listOf("look: <img src=\"0\">${us}answer" to 1L),
            media = mapOf("0" to "a diagram.png"),
            mediaBytes = mapOf("0" to pngBytes)
        )

        val question = AnkiPackageReader.read(apkg).file.papers.single().let(::allQuestions).single()

        assertEquals("image is the question's own, not markup in the text", "look:", question.text)
        assertTrue(
            "image inlined: ${question.image}",
            question.image!!.startsWith("data:image/png;base64,")
        )
    }

    @Test
    fun modernSchema14PackageIsReadFromNormalisedTables() {
        val db = newCollection(14)
        db.execSQL("create table fields (ntid integer not null, ord integer not null, name text not null, primary key (ntid, ord))")
        db.execSQL("create table notetypes (id integer primary key, name text not null, config blob)")
        db.execSQL("create table templates (ntid integer not null, ord integer not null, qfmt text not null, primary key (ntid, ord))")
        db.execSQL("create table decks (id integer primary key, name text not null, mtime_secs integer not null, usn integer not null, common blob not null, kind blob not null)")
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
        assertEquals(listOf("France", "Paris"), questions.map { it.options.single().text })
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

    @Test
    fun aZstdCompressedCollectionIsRejectedWithAnActionableMessage() {
        val zstd = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) + ByteArray(8)
        val e = runCatching { AnkiPackageReader.read(zipOf("collection.anki21b" to zstd)) }
        val message = e.exceptionOrNull()?.message.orEmpty()
        assertTrue("message suggests .apkg: $message", ".apkg" in message)
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
        options = options.map { OptionDto(it.id, it.text, it.image) },
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
     * Builds a package the way Anki 2.1 wrote one: a plain zip holding
     * `collection.anki2` and a `media` manifest mapping numeric entry names to
     * original filenames.
     */
    private fun legacyPackage(
        models: String = """{"1":{"id":1,"name":"Basic","type":0,"flds":[{"name":"Front","ord":0},{"name":"Back","ord":1}],"tmpls":[{"name":"Card 1","ord":0,"qfmt":"{{Front}}","afmt":"{{Back}}"}]}}""",
        decks: String = """{"1":{"id":1,"name":"Default"}}""",
        notes: List<Pair<String, Long>> = emptyList(),
        noteTags: String = " mcqapp ",
        cards: List<ClozeCard> = emptyList(),
        skipAutoCard: Boolean = false,
        media: Map<String, String> = emptyMap(),
        mediaBytes: Map<String, ByteArray> = emptyMap(),
        /** When the collection was created; a review card's due counts from it. */
        crtSeconds: Long = 0L,
        /** Scheduling and review-log rows, which the defaults above leave new. */
        extraSql: List<String> = emptyList()
    ): ByteArray {
        val db = newCollection(11, crtSeconds)
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
        val collection = bytesOf(db)
        return zipOf(
            "collection.anki2" to collection,
            "media" to mediaJson(media).toByteArray(),
            *mediaBytes.map { it.key to it.value }.toTypedArray()
        )
    }

    /** Anki 2.1.x schema 11 collection, matching Anki's own `schema11.sql`. */
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
                "insert into revlog values (1, 1001, -1, 3, 5, 1, 2600, ${crt - 3600}, 0)"
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
        val apkg = AnkiPackageWriter.write(
            paper,
            AnkiDtoMapper.flattenQuestions(paper),
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
