package com.mcqapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.io.AnkiApkgImporter
import com.mcqapp.data.io.CardScheduleDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Passage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The streamed `.apkg` reader must write exactly what the whole-file reader
 * writes.
 *
 * `.apkg` used to be the one import read whole: the archive into a map, every
 * picture into a data URI on one DTO, that DTO beside a whole-bank hash set. It
 * now streams — the archive to temp files, media spooled once, the collection
 * walked deck by deck into the shared per-batch writer. Two readers over the
 * same package is exactly the kind of change that drifts: a paper whose root
 * deck keeps its questions, a level with no deck row, a note whose cards sit in
 * two decks, a cardless note folded into the default deck, a passage's first
 * member, the schedule that follows an accepted question. Each is cheap to get
 * subtly wrong and invisible until a user's deck comes back different.
 *
 * So both readers are run over the same fixtures and their databases are
 * compared table by table. The whole-file [AnkiPackageReader.read] stays as the
 * oracle; this test is the gate its doc promised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnkiStreamEquivalenceTest {

    private lateinit var context: Context
    private val open = mutableListOf<AppDatabase>()

    /**
     * Both readers must be handed the same instant. A card whose last review is
     * not in the log (our own export writes the card's progress but no revlog
     * row) gets its `lastReviewedAt` anchored at "now minus its interval", so a
     * real clock would make the two imports differ by however long apart they
     * ran — a clock reading, not a behavioural difference.
     */
    private val NOW = 1_760_000_000_000L

    private val us = com.mcqapp.data.anki.AnkiPackageWriter.FIELD_SEPARATOR
    private val png1x1 =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    private val pngBytes = java.util.Base64.getDecoder().decode(png1x1.substringAfterLast(","))

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun teardown() {
        open.forEach { it.close() }
        open.clear()
    }

    private fun newDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    /** The old path: read the whole package, then import the whole DTO. */
    private fun wholeFile(bytes: ByteArray): AppDatabase = runBlocking {
        val db = newDb()
        val read = AnkiPackageReader.read(bytes, nowMillis = NOW)
        Importer(db).import(read.file, read.scheduling)
        db
    }

    /** The new path: stream the package into the same writer the JSON path uses. */
    private fun streamed(bytes: ByteArray): AppDatabase = runBlocking {
        val db = newDb()
        AnkiApkgImporter(db).import({ ByteArrayInputStream(bytes) }, nowMillis = NOW)
        db
    }

    private fun assertSameDatabase(bytes: ByteArray, label: String) {
        val expected = dump(wholeFile(bytes))
        val actual = dump(streamed(bytes))
        if (expected != actual) {
            val onlyExpected = expected - actual.toSet()
            val onlyActual = actual - expected.toSet()
            fail(
                "$label: the streamed import wrote a different database\n" +
                    "  only in the whole-file import (${onlyExpected.size}):\n" +
                    onlyExpected.take(20).joinToString("\n") { "    $it" } +
                    "\n  only in the streamed import (${onlyActual.size}):\n" +
                    onlyActual.take(20).joinToString("\n") { "    $it" }
            )
        }
        assertEquals(
            "$label: the streamed import must write something",
            true,
            expected.any { it.startsWith("questions: ") }
        )
    }

    /**
     * Every row of every import-relevant table, order-independent, with the
     * columns that legitimately differ between two imports blanked: a paper's
     * `createdAt` is "now", and a rowid-backed attempt/result id depends on
     * insertion order. A differing value — including a question's `sortOrder` —
     * still shows up.
     */
    private fun dump(db: AppDatabase): List<String> {
        val raw = db.openHelper.writableDatabase
        val out = mutableListOf<String>()
        for (table in COMPARED_TABLES) {
            raw.query("select * from `$table`").use { c ->
                val names = c.columnNames
                while (c.moveToNext()) {
                    val cells = names.indices.mapNotNull { i ->
                        val name = names[i]
                        val volatile = name == "createdAt" ||
                            (table == "attempts" && name == "id") ||
                            (table == "question_results" && name == "id")
                        if (volatile) null else "$name=${c.getString(i)}"
                    }
                    out += "$table: " + cells.joinToString(",")
                }
            }
        }
        return out.sorted()
    }

    // ---- our own export ---------------------------------------------------

    /**
     * A payload export: passages, images, two option styles, a nested category,
     * a scheduled card. Exercises the payload branch of the reader and the
     * media index on a package our own writer produced.
     */
    @Test
    fun ourOwnExportStreamsToTheSameDatabase() {
        val paper = PaperDto(
            id = "p1",
            title = "Science",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "Biology",
                    questions = listOf(
                        QuestionDto(
                            id = "q1",
                            text = "Which organelle makes ATP?",
                            image = png1x1,
                            options = listOf(
                                OptionDto("a", "Mitochondrion"),
                                OptionDto("b", "Ribosome"),
                                OptionDto("c", "Nucleus")
                            ),
                            correctOptionIds = listOf("a"),
                            explanation = "ATP synthase sits on the inner membrane.",
                            difficulty = "hard",
                            marks = 2.0,
                            tags = listOf("energy")
                        ),
                        QuestionDto(
                            id = "q2",
                            text = "Where does glycolysis happen?",
                            passageId = "passage-1",
                            options = listOf(OptionDto("a", "Cytosol"), OptionDto("b", "Nucleus")),
                            correctOptionIds = listOf("a"),
                            explanation = "In the cytoplasm."
                        )
                    )
                ),
                CategoryDto(
                    id = "c2",
                    title = "Physics",
                    parentId = "c1",
                    questions = listOf(
                        QuestionDto(
                            id = "q3",
                            text = "F = ma is Newton's which law?",
                            options = listOf(OptionDto("a", "Second"), OptionDto("b", "Third")),
                            correctOptionIds = listOf("a"),
                            explanation = "The second law."
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
                elements = listOf(ContentElement.TextElement("Cells make ATP."))
            )
        )
        val now = 1_700_000_000_000L
        val scheduling = mapOf(
            "q1" to CardScheduleDto(
                ease = 2.5,
                intervalDays = 5,
                dueAt = now + 5 * 86_400_000L,
                reps = 7,
                lapses = 1,
                leech = false,
                lastReviewedAt = now
            )
        )

        assertSameDatabase(apkgOf(paper, scheduling, passages), "own export")
    }

    // ---- a foreign, hand-built package ------------------------------------

    /**
     * The shapes the grouping has to get right, all in one package: several
     * top-level decks, a nested category, a level with no deck of its own, a
     * note whose cards sit in two decks, a cardless note folded into the default
     * deck, a blank-front note with no question, an unlisted orphan deck, a
     * cloze note with two deletions, a reviewed card with a review log, and
     * media referenced by its zip entry name.
     */
    @Test
    fun aForeignPackageWithEveryDeckShapeStreamsToTheSameDatabase() {
        assertSameDatabase(foreignPackage(), "foreign package")
    }

    /**
     * The same collection written with `ver = 14`. Schema 14 has the JSON in
     * `col` but none of the notetype/deck tables (Anki's schema15 upgrade
     * creates them and then sets ver = 15), so both readers must treat it as
     * the legacy shape. The old `ver >= 14` gate sent it to `select 1 from
     * fields`, which fails for a collection that has no `fields` table.
     */
    @Test
    fun aSchema14ForeignPackageStreamsToTheSameDatabase() {
        val bytes = foreignPackage(ver = 14)
        assertSameDatabase(bytes, "schema-14 foreign package")
        // Equivalence alone cannot see a bug both sides share. The
        // whole-file reader keeps the paper's own deck in the paper's
        // top-level question list, and the importer used to drop those, so
        // the streamed import dropping them too made the two databases
        // equal. The root deck's questions must actually be in the library.
        val texts = questionTexts(streamed(bytes))
        for (rootDeckQuestion in listOf("2 + 2?", "Which organelle makes ATP?", "Picture?")) {
            assertTrue(
                "the paper's own deck's question ($rootDeckQuestion) must reach the library",
                texts.any { it.contains(rootDeckQuestion) }
            )
        }
    }

    /** Every question's stored text, as the library holds it. */
    private fun questionTexts(db: AppDatabase): List<String> =
        db.openHelper.writableDatabase.query("select text from questions").use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(0) ?: "")
            }
        }

    private fun foreignPackage(ver: Int = 11): ByteArray = zipOf(
        "collection.anki2" to foreignCollection(ver),
        "media" to """{"0":"pic.png"}""".toByteArray(),
        "0" to pngBytes
    )

    /**
     * The compatibility stub a modern export writes into `collection.anki2`
     * beside the real `collection.anki21`: one note, no deck content, blank
     * back. Reading it is what produced a zero-question import.
     */
    private fun stubCollection(): ByteArray {
        val db = newCollection(11, crtSeconds = 0)
        db.execSQL(
            "update col set models = ?, decks = ?",
            arrayOf<Any>(
                """{"1":{"id":1,"name":"Basic","type":0,"flds":[{"name":"Front","ord":0},{"name":"Back","ord":1}],"tmpls":[{"name":"Card 1","ord":0}]}}""",
                """{"1":{"id":1,"name":"Default"}}"""
            )
        )
        db.addNote(
            id = 1,
            mid = 1,
            tags = "",
            flds = "Please update to the latest Anki version, then import the " +
                ".colpkg/.apkg file again.${us}",
            cards = listOf(Triple(1L, 1L, 0))
        )
        return bytesOf(db)
    }

    /**
     * A modern package in Anki's authentic order: `meta`, the real
     * `collection.anki21`, the compatibility stub in `collection.anki2`, then
     * `media`. The old streamed reader preferred `collection.anki2` by name
     * regardless of order, so it read the stub and lost the deck; the old
     * whole-file reader happened to take the first matching zip entry, which the
     * authentic order gets right but a stub-first package does not (that case is
     * pinned in AnkiPackageReaderTest).
     */
    @Test
    fun aStubBesideTheModernCollectionStreamsToTheSameDatabase() {
        val bytes = zipOf(
            "meta" to byteArrayOf(0x08, 0x02),
            "collection.anki21" to foreignCollection(ver = 11),
            "collection.anki2" to stubCollection(),
            "media" to """{"0":"pic.png"}""".toByteArray(),
            "0" to pngBytes
        )

        assertSameDatabase(bytes, "compatibility stub + modern collection")
        val texts = questionTexts(streamed(bytes))
        assertTrue(
            "the stub's text must never reach the library: $texts",
            texts.none { it.contains("Please update to the latest Anki version") }
        )
        assertTrue("the real collection's questions import", texts.any { it.contains("2 + 2?") })
    }

    /**
     * The `.anki21b` shape: collection, manifest and every picture are zstd
     * frames. Both readers must decode all three; the streamed one decodes the
     * collection into its spooled file rather than asking SQLite to open a
     * compressed database.
     */
    @Test
    fun aZstdCompressedPackageStreamsToTheSameDatabase() {
        val bytes = zipOf(
            "collection.anki21b" to zstdFrame(foreignCollection(ver = 11)),
            "media" to zstdFrame("""{"0":"pic.png"}""".toByteArray()),
            "0" to zstdFrame(pngBytes)
        )

        assertSameDatabase(bytes, "zstd package")
        assertTrue(
            "the compressed collection's questions import",
            questionTexts(streamed(bytes)).any { it.contains("2 + 2?") }
        )
    }


    /**
     * A schema-18 package whose deck names use the Unit Separator (`0x1f`)
     * instead of `::`. Both readers must normalise it to the same hierarchy:
     * one "Bank" paper with "Science" and "Visual Reasoning" under it.
     */
    @Test
    fun unitSeparatorDeckNamesStreamToTheSameTitles() {
        val bytes = unitSeparatorPackage()

        assertSameDatabase(bytes, "unit-separator deck names")

        val whole = AnkiPackageReader.read(bytes, nowMillis = NOW).file
        val sink = TitleSink()
        runBlocking {
            AnkiPackageReader.stream(ByteArrayInputStream(bytes), sink, nowMillis = NOW)
        }

        assertEquals(
            whole.papers.map { it.title },
            sink.paperTitles
        )
        assertEquals(
            whole.papers.flatMap { paper -> paper.categories.map { it.title } },
            sink.categoryTitles
        )
        assertEquals(listOf("Bank"), sink.paperTitles)
        assertEquals(listOf("Science", "Visual Reasoning"), sink.categoryTitles)
        assertTrue(
            "no title may carry the Unit Separator: ${sink.paperTitles + sink.categoryTitles}",
            (sink.paperTitles + sink.categoryTitles).none { '\u001f' in it }
        )
    }

    private fun unitSeparatorPackage(): ByteArray {
        val db = newCollection(18, crtSeconds = 1_700_000_000L)
        db.execSQL(
            "create table fields (ntid integer not null, ord integer not null, name text not null, " +
                "primary key (ntid, ord))"
        )
        db.execSQL("create table notetypes (id integer primary key, name text not null, config blob)")
        db.execSQL(
            "create table templates (ntid integer not null, ord integer not null, qfmt text not null, " +
                "primary key (ntid, ord))"
        )
        db.execSQL(
            "create table decks (id integer primary key, name text not null, mtime_secs integer not null, " +
                "usn integer not null, common blob not null, kind blob not null)"
        )
        db.execSQL("insert into notetypes values (1, 'Basic', x'')")
        db.execSQL("insert into fields values (1, 0, 'Front'), (1, 1, 'Back')")
        db.execSQL("insert into templates values (1, 0, '{{Front}}')")
        db.execSQL(
            "insert into decks values (1, 'Default', 0, 0, x'', x''), " +
                "(54, 'Bank', 0, 0, x'', x''), " +
                "(55, 'Bank${us}Science', 0, 0, x'', x''), " +
                "(56, 'Bank${us}Visual Reasoning', 0, 0, x'', x'')"
        )
        db.addNote(
            id = 100, mid = 1, tags = " mcqapp ",
            flds = "Q1?${us}&#10003; yes<br>&#10007; no",
            cards = listOf(Triple(200L, 55L, 0))
        )
        db.addNote(
            id = 101, mid = 1, tags = " mcqapp ",
            flds = "Q2?${us}&#10003; yes<br>&#10007; no",
            cards = listOf(Triple(201L, 56L, 0))
        )
        return zipOf("collection.anki21" to bytesOf(db))
    }

    /** Collects the paper and category titles the streamed reader emits. */
    private class TitleSink : AnkiPackageReader.StreamSink {
        val paperTitles = mutableListOf<String>()
        val categoryTitles = mutableListOf<String>()

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
        ) = Unit
    }

    private fun foreignCollection(ver: Int = 11): ByteArray {
        val db = newCollection(ver, crtSeconds = 1_700_000_000L)
        val models =
            """{"1":{"id":1,"name":"Basic","type":0,"flds":[{"name":"Front","ord":0},{"name":"Back","ord":1}],"tmpls":[{"name":"Card 1","ord":0}]},""" +
                """ "2":{"id":2,"name":"Cloze","type":1,"flds":[{"name":"Text","ord":0}],"tmpls":[{"name":"Cloze","ord":0}]}}"""
        val decks =
            """{"1":{"id":1,"name":"Default"},""" +
                """"2":{"id":2,"name":"Science"},""" +
                """"3":{"id":3,"name":"Science::Biology"},""" +
                """"4":{"id":4,"name":"Science::Biology::Cells"},""" +
                """"5":{"id":5,"name":"History"},""" +
                """"6":{"id":6,"name":"Filtered","dyn":1},""" +
                """"7":{"id":7,"name":"Empty"}}"""
        db.execSQL("update col set models = ?, decks = ?", arrayOf<Any>(models, decks))

        fun marker(front: String, correct: String, wrong: String) =
            "$front${us}&#10003; $correct<br>&#10007; $wrong"

        // Science's own deck: a plain question, and it also shares a note with
        // Biology (two cards, one note) — both copies carry the same id.
        db.addNote(
            id = 1, mid = 1, tags = " mcqapp anki-difficulty-hard ",
            flds = marker("2 + 2?", "4", "five"),
            cards = listOf(Triple(1001L, 2L, 0))
        )
        db.addNote(
            id = 2, mid = 1, tags = " mcqapp ",
            flds = marker("Which organelle makes ATP?", "Mitochondrion", "Ribosome"),
            cards = listOf(Triple(1002L, 2L, 0))
        )
        // An image referenced by its zip entry name.
        db.addNote(
            id = 12, mid = 1, tags = " mcqapp ",
            flds = "<img src=\"0\">" + marker("Picture?", "Mitochondrion", "Ribosome"),
            cards = listOf(Triple(1012L, 2L, 0))
        )
        // Biology and Cells, one per level; a reviewed card under Biology.
        db.addNote(
            id = 3, mid = 1, tags = " mcqapp ",
            flds = marker("Glycolysis?", "Cytosol", "Nucleus"),
            cards = listOf(Triple(1003L, 3L, 0))
        )
        db.addNote(
            id = 4, mid = 1, tags = " mcqapp ",
            flds = marker("Cells are?", "Small", "Big"),
            cards = listOf(Triple(1004L, 4L, 0))
        )
        // A cloze note whose two cards sit in two different top-level decks:
        // one deletion each, so the card ords are distinct as Anki's own are
        // (a note cannot hold two cards for one template).
        db.addNote(
            id = 5, mid = 2, tags = " mcqapp ",
            flds = "Shared: {{c1::first}} and {{c2::second}}?",
            cards = listOf(Triple(1005L, 2L, 0), Triple(1006L, 5L, 1))
        )
        // No card at all: the whole-file reader folds it into the default deck.
        db.addNote(
            id = 6, mid = 1, tags = " mcqapp ",
            flds = marker("Cardless?", "True", "False"),
            cards = emptyList()
        )
        // A blank front: no question, but the note is still in the collection.
        db.addNote(
            id = 7, mid = 1, tags = " mcqapp ",
            flds = "${us}&#10003; 4<br>&#10007; five",
            cards = listOf(Triple(1007L, 2L, 0))
        )
        // A cloze note with two deletions and a card per deletion.
        db.addNote(
            id = 8, mid = 2, tags = " mcqapp ",
            flds = "The capital of France is {{c1::Paris}} and of Italy is {{c2::Rome}}.",
            cards = listOf(Triple(1008L, 5L, 0), Triple(1009L, 5L, 1))
        )
        // A deck the collection does not list (a filtered deck's leftovers).
        db.addNote(
            id = 9, mid = 1, tags = " mcqapp ",
            flds = marker("Orphan?", "A", "B"),
            cards = listOf(Triple(1010L, 99L, 0))
        )
        // A second unlisted deck, later: the two fold into one "Imported" paper
        // and only the later one's question survives, the whole-file rule.
        db.addNote(
            id = 13, mid = 1, tags = " mcqapp ",
            flds = marker("Orphan too?", "C", "D"),
            cards = listOf(Triple(1013L, 98L, 0))
        )
        // A deck Anki lists but whose only note has no question: not a paper.
        db.addNote(
            id = 14, mid = 1, tags = " mcqapp ",
            flds = "${us}&#10003; 4<br>&#10007; five",
            cards = listOf(Triple(1014L, 7L, 0))
        )
        // A card with review progress and a review log.
        db.addNote(
            id = 10, mid = 1, tags = " mcqapp ",
            flds = marker("Studied?", "Yes", "No"),
            cards = listOf(Triple(1011L, 3L, 0))
        )
        db.execSQL(
            "update cards set type = 2, queue = 2, due = 5, ivl = 5, factor = 2600, " +
                "reps = 7, lapses = 1 where id = 1011"
        )
        // A real revlog row: id is the review's epoch-millis timestamp, time is
        // how long it took in millis.
        db.execSQL("insert into revlog values (1699996400000, 1011, -1, 3, 5, 1, 2600, 32500, 0)")

        return bytesOf(db)
    }

    // ---- helpers ----------------------------------------------------------

    private fun SQLiteDatabase.addNote(
        id: Long,
        mid: Long,
        tags: String,
        flds: String,
        cards: List<Triple<Long, Long, Int>>
    ) {
        execSQL(
            "insert into notes values (?, ?, ?, 0, 0, ?, ?, '', 0, 0, '')",
            arrayOf<Any>(id, "guid$id", mid, tags, flds)
        )
        cards.forEach { (cardId, did, ord) ->
            execSQL(
                "insert into cards values (?, ?, ?, ?, 0,0,0,0,0,0,0,0,0,0,0,0,0,'')",
                arrayOf<Any>(cardId, id, did, ord.toLong())
            )
        }
    }

    /** Anki 2.1.x schema 11 collection, matching Anki's own `schema11.sql`. */
    private fun newCollection(ver: Int, crtSeconds: Long): SQLiteDatabase {
        val file = File.createTempFile("stream-equivalence", ".anki2")
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "create table col (id integer primary key, crt integer not null, mod integer not null, " +
                "scm integer not null, ver integer not null, dty integer not null, usn integer not null, " +
                "ls integer not null, conf text not null, models text not null, decks text not null, " +
                "dconf text not null, tags text not null)"
        )
        db.execSQL(
            "create table notes (id integer primary key, guid text not null, mid integer not null, " +
                "mod integer not null, usn integer not null, tags text not null, flds text not null, " +
                "sfld integer not null, csum integer not null, flags integer not null, data text not null)"
        )
        db.execSQL(
            "create table cards (id integer primary key, nid integer not null, did integer not null, " +
                "ord integer not null, mod integer not null, usn integer not null, type integer not null, " +
                "queue integer not null, due integer not null, ivl integer not null, factor integer not null, " +
                "reps integer not null, lapses integer not null, left integer not null, odue integer not null, " +
                "odid integer not null, flags integer not null, data text not null)"
        )
        db.execSQL(
            "create table revlog (id integer primary key, cid integer not null, usn integer not null, " +
                "ease integer not null, ivl integer not null, lastIvl integer not null, " +
                "factor integer not null, time integer not null, type integer not null)"
        )
        db.execSQL(
            "insert into col values (1, ?, 0, 0, ?, 0, 0, 0, '{}', '{}', '{}', '{}', '{}')",
            arrayOf<Any>(crtSeconds, ver)
        )
        return db
    }

    private fun bytesOf(db: SQLiteDatabase): ByteArray {
        val path = db.path
        db.close()
        val bytes = File(path).readBytes()
        File(path).delete()
        return bytes
    }

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

    private companion object {
        /** Tables an `.apkg` import writes; everything else is untouched by both. */
        val COMPARED_TABLES = listOf(
            "papers",
            "categories",
            "questions",
            "options",
            "correct_answers",
            "passages",
            "card_state",
            "bookmarks",
            "attempts",
            "question_results",
            "question_stats"
        )
    }
}
