package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.StreamingImporter
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A JSON backup used to carry no review progress at all, so restoring one
 * silently reset every SM-2 schedule, due date and leech flag while the UI
 * called the file a backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSchedulingTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(
            Question(
                id = "q1",
                categoryId = "c1",
                text = "2 + 2?",
                options = listOf(QuestionOption("a", "3"), QuestionOption("b", "4")),
                correctOptionIds = setOf("b")
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun studyOnce() {
        repository.recordStudyReview(
            paperId = "p1",
            questionId = "q1",
            grade = ReviewGrade.GOOD,
            now = 1_700_000_000_000L
        )
    }

    /**
     * The whole-bank JSON as a string, for the parsers that read it. The
     * exporter writes into a stream, so the test reads it back out of one.
     */
    private fun exportAllJson(): String {
        val text = java.io.StringWriter()
        val out = java.io.BufferedWriter(text)
        runBlocking { Exporter(db).exportAllTo(out) }
        out.flush()
        return text.toString()
    }

    @Test
    fun theBackupCarriesReviewProgress() = runBlocking {
        studyOnce()
        val json = exportAllJson()
        val file = LegacyParser.parse(json)
        assertTrue(
            "the backup should carry q1's schedule",
            file.scheduling.containsKey("q1")
        )
        val schedule = file.scheduling.getValue("q1")
        assertTrue("reps should have been exported", schedule.reps > 0)
    }

    @Test
    fun aRestoredBackupKeepsTheSchedule() = runBlocking {
        studyOnce()
        val before = repository.cardState("p1", "q1")!!
        val json = exportAllJson()

        // Fresh database: nothing but the file.
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)

        Importer(db).import(LegacyParser.parse(json))

        val after = repository.cardState("p1", "q1")
        assertTrue("the card should exist after restore", after != null)
        assertEquals(before.reps, after!!.reps)
        assertEquals(before.ease, after.ease, 0.0001)
        assertEquals(before.intervalDays, after.intervalDays)
        assertEquals(before.lastReviewedAt, after.lastReviewedAt)
        // The FSRS memory model travels with the backup too.
        assertEquals(before.difficulty, after.difficulty, 0.0001)
        assertEquals(before.stability, after.stability, 0.0001)
        assertEquals(before.state, after.state)
        assertEquals(before.reviewCount, after.reviewCount)
    }

    /**
     * The import screen now streams a large "Export all" JSON instead of
     * holding it as one DTO; that path must keep the review progress the export
     * carries, exactly as the whole-file restore does.
     */
    @Test
    fun aStreamedBackupKeepsTheSchedule() = runBlocking {
        studyOnce()
        val before = repository.cardState("p1", "q1")!!
        val json = exportAllJson()

        // Fresh database: nothing but the file.
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)

        StreamingImporter(db, captureSchedules = true)
            .import({ java.io.ByteArrayInputStream(json.toByteArray()) })

        val after = repository.cardState("p1", "q1")
        assertTrue("the card should exist after a streamed restore", after != null)
        assertEquals(before.reps, after!!.reps)
        assertEquals(before.ease, after.ease, 0.0001)
        assertEquals(before.intervalDays, after.intervalDays)
        assertEquals(before.lastReviewedAt, after.lastReviewedAt)
        assertEquals(before.difficulty, after.difficulty, 0.0001)
        assertEquals(before.stability, after.stability, 0.0001)
        assertEquals(before.state, after.state)
        assertEquals(before.reviewCount, after.reviewCount)
    }

    @Test
    fun anUntouchedQuestionIsNotGivenASchedule() = runBlocking {
        repository.saveQuestion(
            Question(
                id = "q2",
                categoryId = "c1",
                text = "3 + 3?",
                options = listOf(QuestionOption("a", "5"), QuestionOption("b", "6")),
                correctOptionIds = setOf("b")
            )
        )
        studyOnce()
        val file = LegacyParser.parse(exportAllJson())
        assertEquals(setOf("q1"), file.scheduling.keys)
    }

    @Test
    fun aForeignFileWithoutSchedulingStillImports() = runBlocking {
        val json = """
            {"papers":[{"id":"p2","title":"Other","categories":[{"id":"c9","title":"Other",
            "questions":[{"id":"q9","text":"5 + 5?","options":[{"id":"a","text":"9"},{"id":"b","text":"10"}],
            "correct":"b"}]}]}]}
        """.trimIndent()
        val report = Importer(db).import(LegacyParser.parse(json))
        assertEquals(1, report.newQuestions)
        assertEquals(0, db.cardStateDao().getAll().size)
    }

    /**
     * The streaming export writes the bookmark array one id at a time (it used
     * to encode the whole list into a single String). The document must still
     * parse and still carry the bookmarks.
     */
    @Test
    fun theStreamedExportCarriesBookmarks() = runBlocking {
        repository.saveQuestion(
            Question(
                id = "q2",
                categoryId = "c1",
                text = "3 + 3?",
                options = listOf(QuestionOption("a", "5"), QuestionOption("b", "6")),
                correctOptionIds = setOf("b")
            )
        )
        db.bookmarkDao().add(com.mcqapp.data.local.BookmarkEntity("q1", createdAt = 5))
        db.bookmarkDao().add(com.mcqapp.data.local.BookmarkEntity("q2", createdAt = 9))

        val file = LegacyParser.parse(exportAllJson())
        assertEquals(setOf("q1", "q2"), file.bookmarks.toSet())
    }

    /**
     * A backup-style file whose member order puts `scheduling` BEFORE `papers`
     * (the parser owns no member order elsewhere) applied its schedule pages
     * at streaming time against the accepted-question map while it was still
     * filling: every entry was skipped and the review progress silently
     * discarded, while the same bytes through the whole-file importer restored
     * fine. The pages are buffered until the questions have settled; both
     * orders must restore identically, and the control pins that the
     * papers-first order (the app's own writer's) did not regress.
     *
     * This opens SQLite, so it cannot run on this aarch64 host — CI is the
     * authority. Mutant: apply the page at streaming time as before and the
     * reversed-order half reports restoredSchedules 0 with no card state.
     */
    @Test
    fun schedulesPrecedingThePapersAreStillRestored() = runBlocking {
        val papers = """
            "papers": [
              {
                "id": "p1",
                "title": "Paper",
                "description": "",
                "duration": 30,
                "negative": 0.0,
                "categories": [
                  {"id": "c1", "title": "Cat", "questions": [
                    {"id": "q1", "text": "2 + 2?", "options": ["3", "4"], "correctOptionIds": ["b"]}
                  ]}
                ]
              }
            ]
        """.trimIndent()
        val schedules = """
            "scheduling": {
              "q1": {
                "ease": 2.5, "intervalDays": 3, "dueAt": 1700000000000,
                "reps": 4, "lapses": 1, "leech": false, "lastReviewedAt": 1690000000000,
                "difficulty": 6.1, "stability": 12.3, "cardState": "REVIEW", "reviewCount": 4
              }
            }
        """.trimIndent()
        val goodOrder = "{\n\"version\": 3,\n$papers,\n$schedules\n}"
        val badOrder = "{\n\"version\": 3,\n$schedules,\n$papers\n}"

        // Control: the writer's own order still restores exactly as it did.
        var report = StreamingImporter(db, captureSchedules = true)
            .import({ java.io.ByteArrayInputStream(goodOrder.toByteArray()) })
        assertEquals(1, report.restoredSchedules)
        val good = repository.cardState("p1", "q1")!!

        // Fresh database, then the reversed member order.
        rebuildFreshDb()
        report = StreamingImporter(db, captureSchedules = true)
            .import({ java.io.ByteArrayInputStream(badOrder.toByteArray()) })
        assertEquals("every schedule entry is restored once", 1, report.restoredSchedules)
        val bad = repository.cardState("p1", "q1")!!
        assertEquals(good.reps, bad.reps)
        assertEquals(good.ease, bad.ease, 1e-9)
        assertEquals(good.intervalDays, bad.intervalDays)
        assertEquals(good.dueAt, bad.dueAt)
        assertEquals(good.lapses, bad.lapses)
        assertEquals(good.lastReviewedAt, bad.lastReviewedAt)
        assertEquals(good.difficulty, bad.difficulty, 1e-9)
        assertEquals(good.stability, bad.stability, 1e-9)
        assertEquals(good.state, bad.state)
        assertEquals(good.reviewCount, bad.reviewCount)
    }

    /** The fresh-database trick aRestoredBackupKeepsTheSchedule already uses. */
    private fun rebuildFreshDb() {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }
}
