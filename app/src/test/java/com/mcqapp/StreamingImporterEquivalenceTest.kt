package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.ImportOverrides
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.StreamingImporter
import com.mcqapp.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * The streaming importer must leave the database in exactly the state the
 * whole-file importer would, otherwise the two paths disagree about what is a
 * duplicate, where a passage lives, or how questions are ordered. Each test
 * imports the same bytes through both and diffs the resulting tables row by
 * row, ignoring only the columns that legitimately vary between two runs
 * (creation timestamps and autoincrement ids).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingImporterEquivalenceTest {

    private lateinit var wholeFileDb: AppDatabase
    private lateinit var streamingDb: AppDatabase

    @Before
    fun setup() {
        wholeFileDb = newDb()
        streamingDb = newDb()
    }

    @After
    fun teardown() {
        wholeFileDb.close()
        streamingDb.close()
    }

    private fun newDb(): AppDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private suspend fun wholeFileImport(
        db: AppDatabase,
        json: String,
        updateAnswers: Boolean = false,
        times: Int = 1
    ) {
        val file: McqFileDto = LegacyParser.parse(ByteArrayInputStream(json.toByteArray()))
        repeat(times) { Importer(db, updateAnswersOnDuplicate = updateAnswers).import(file) }
    }

    private suspend fun streamingImport(
        db: AppDatabase,
        json: String,
        updateAnswers: Boolean = false,
        times: Int = 1,
        batchSize: Int = LegacyParser.IMPORT_BATCH_SIZE,
        captureSchedules: Boolean = false
    ) {
        val importer = StreamingImporter(
            db,
            updateAnswersOnDuplicate = updateAnswers,
            batchSize = batchSize,
            captureSchedules = captureSchedules
        )
        repeat(times) {
            importer.import(source = { ByteArrayInputStream(json.toByteArray()) })
        }
    }

    private suspend fun assertSameState() {
        assertEquals(dump(wholeFileDb), dump(streamingDb))
    }

    /** Every row of every import-relevant table, order-independent. */
    private fun dump(db: AppDatabase): List<String> {
        val sd = db.openHelper.writableDatabase
        val tables = listOf(
            "papers", "categories", "questions", "options", "correct_answers",
            "passages", "bookmarks", "attempts", "question_results", "question_stats",
            "card_state"
        )
        val out = mutableListOf<String>()
        for (table in tables) {
            sd.query("SELECT * FROM $table").use { cursor ->
                val columns = cursor.columnNames
                while (cursor.moveToNext()) {
                    val cells = columns.indices.mapNotNull { i ->
                        val name = columns[i]
                        val volatile = name == "createdAt" ||
                            (table == "attempts" && name == "id") ||
                            (table == "question_results" && name == "id")
                        if (volatile) null else "$name=${cursor.getString(i)}"
                    }
                    out += "$table: " + cells.joinToString(",")
                }
            }
        }
        return out.sorted()
    }

    private val richBank = """
    {
      "version": 2,
      "papers": [
        {
          "id": "p1",
          "title": "Paper",
          "description": "d",
          "duration": 45,
          "negative": 0.25,
          "categories": [
            {"id": "a", "title": "A", "questions": [
              {"id": "q1", "text": "One", "options": ["x", "y"], "correctOptionIds": ["x"], "passageId": "ps1"},
              {"id": "q2", "text": "Two", "options": ["x", "y"], "correctOptionIds": ["y"], "difficulty": "hard", "marks": 3, "tags": ["t1", "t2"]}
            ]},
            {"id": "b", "title": "B", "questions": [
              {"id": "q3", "text": "Three", "options": ["x", "y"], "correctOptionIds": ["x"], "passageId": "ps1"}
            ]},
            {"id": "c", "title": "C"}
          ],
          "questions": [
            {"id": "root1", "text": "Root", "options": ["x", "y"], "correctOptionIds": ["x"]}
          ]
        }
      ],
      "passages": [
        {"id": "ps1", "title": "Passage", "text": "Body"}
      ],
      "bookmarks": ["q1", "q2"],
      "attempts": [
        {"paperId": "p1", "title": "Attempt", "totalQuestions": 1, "correctCount": 1,
         "results": [{"questionId": "q1", "text": "One", "optionsJson": "[]",
                      "correctOptionIds": "x", "selectedOptionIds": "x", "isCorrect": true}]}
      ]
    }
    """.trimIndent()

    @Test
    fun aFreshRichImportMatches() = runBlocking {
        wholeFileImport(wholeFileDb, richBank)
        streamingImport(streamingDb, richBank)
        assertSameState()
    }

    @Test
    fun splitAcrossManyBatchesMatches() = runBlocking {
        val n = 11
        val questions = (1..n).joinToString(",") {
            """{"id":"q$it","text":"Q$it","options":["a","b"],"correctOptionIds":["a"]}"""
        }
        val json = """{"papers":[{"id":"p","title":"P","categories":[{"id":"c","title":"C","questions":[$questions]}]}]}"""
        wholeFileImport(wholeFileDb, json)
        streamingImport(streamingDb, json, batchSize = 1)
        assertSameState()
    }

    @Test
    fun reimportingAnIdenticalBankMatches() = runBlocking {
        wholeFileImport(wholeFileDb, richBank, times = 2)
        streamingImport(streamingDb, richBank, times = 2)
        assertSameState()
        // Reports are data classes; the second pass is the interesting one.
        val wholeReport = Importer(wholeFileDb).import(
            LegacyParser.parse(ByteArrayInputStream(richBank.toByteArray()))
        )
        val streamReport = StreamingImporter(streamingDb)
            .import(source = { ByteArrayInputStream(richBank.toByteArray()) })
        assertEquals(wholeReport, streamReport)
        assertSameState()
    }

    @Test
    fun aChangedAnswerKeyRefreshesIdentically() = runBlocking {
        val corrected = richBank.replace(
            """{"id": "q1", "text": "One", "options": ["x", "y"], "correctOptionIds": ["x"], "passageId": "ps1"}""",
            """{"id": "q1", "text": "One", "options": ["x", "y"], "correctOptionIds": ["y"], "passageId": "ps1"}"""
        )
        wholeFileImport(wholeFileDb, richBank)
        wholeFileImport(wholeFileDb, corrected, updateAnswers = true)
        streamingImport(streamingDb, richBank)
        streamingImport(streamingDb, corrected, updateAnswers = true)
        assertSameState()
    }

    @Test
    fun aChangedQuestionBodyUpdatesIdentically() = runBlocking {
        val changed = richBank.replace("\"text\": \"Three\"", "\"text\": \"Three (edited)\"")
        wholeFileImport(wholeFileDb, richBank)
        wholeFileImport(wholeFileDb, changed)
        streamingImport(streamingDb, richBank)
        streamingImport(streamingDb, changed)
        assertSameState()
    }

    @Test
    fun aSecondPaperToTheSameLibraryMatches() = runBlocking {
        val second = """{"papers":[{"id":"p2","title":"Second","categories":[{"id":"z","title":"Z","questions":[{"id":"z1","text":"Zed","options":["a","b"],"correctOptionIds":["a"]}]}]}]}"""
        wholeFileImport(wholeFileDb, richBank)
        wholeFileImport(wholeFileDb, second)
        streamingImport(streamingDb, richBank)
        streamingImport(streamingDb, second)
        assertSameState()
    }

    private val singleCategoryBank = """
    {
      "papers": [
        {
          "id": "p1",
          "title": "Original",
          "description": "orig",
          "duration": 10,
          "negative": 0.0,
          "categories": [
            {"id": "c", "title": "Original Cat", "questions": [
              {"id": "q1", "text": "One", "options": ["a", "b"], "correctOptionIds": ["a"]},
              {"id": "q2", "text": "Two", "options": ["a", "b"], "correctOptionIds": ["b"]},
              {"id": "q3", "text": "Three", "options": ["a", "b"], "correctOptionIds": ["a"]}
            ]}
          ]
        }
      ]
    }
    """.trimIndent()

    /**
     * The preview overrides are the streaming twin of assembling an edited
     * copy: meta, category rename and a deleted question. Importing the
     * assembled DTO through the whole-file importer must land the same rows.
     */
    @Test
    fun previewMetaDeleteAndRenameMatchTheAssembledFile() = runBlocking {
        val original = LegacyParser.parse(ByteArrayInputStream(singleCategoryBank.toByteArray()))
        val paper = original.papers.first()
        val assembled = original.copy(
            papers = listOf(
                paper.copy(
                    title = "New Title",
                    description = "New Desc",
                    durationMinutes = 30,
                    negativeMarking = 0.5,
                    categories = listOf(
                        paper.categories.first().copy(
                            title = "Renamed",
                            questions = paper.categories.first().questions.filter { it.id != "q2" }
                        )
                    )
                )
            )
        )
        Importer(wholeFileDb).import(assembled)

        StreamingImporter(
            streamingDb,
            overrides = ImportOverrides(
                paperTitle = "New Title",
                paperDescription = "New Desc",
                durationMinutes = 30,
                negativeMarking = 0.5,
                categoryTitle = "Renamed",
                deletedIds = setOf("q2")
            )
        ).import(source = { ByteArrayInputStream(singleCategoryBank.toByteArray()) })
        assertSameState()
    }

    /**
     * The library's "Export all" shape: more than one paper, and review
     * progress in the tail. The whole-file importer applies `scheduling` to the
     * questions it accepts; a streamed import that did not would silently drop
     * every schedule the export carried.
     */
    private val scheduledTwoPaperBank = """
    {
      "version": 2,
      "papers": [
        {
          "id": "p1",
          "title": "First",
          "categories": [
            {"id": "c1", "title": "C1", "questions": [
              {"id": "q1", "text": "One", "options": ["a","b"], "correctOptionIds": ["a"]},
              {"id": "q2", "text": "Two", "options": ["a","b"], "correctOptionIds": ["b"]}
            ]}
          ]
        },
        {
          "id": "p2",
          "title": "Second",
          "categories": [
            {"id": "c2", "title": "C2", "questions": [
              {"id": "q3", "text": "Three", "options": ["a","b"], "correctOptionIds": ["a"]}
            ]}
          ]
        }
      ],
      "scheduling": {
        "q1": {"ease": 2.6, "intervalDays": 5, "dueAt": 1000, "reps": 3, "lapses": 1,
               "cardState": "REVIEW", "lastReviewedAt": 900, "difficulty": 4.5,
               "stability": 12.5, "reviewCount": 4},
        "q3": {"ease": 2.4, "intervalDays": 2, "dueAt": 2000, "reps": 1, "lapses": 0,
               "cardState": "LEARNING", "lastReviewedAt": 1900, "difficulty": 6.0,
               "stability": 3.25, "reviewCount": 1}
      }
    }
    """.trimIndent()

    @Test
    fun aMultiPaperBankWithReviewProgressMatches() = runBlocking {
        wholeFileImport(wholeFileDb, scheduledTwoPaperBank)
        streamingImport(streamingDb, scheduledTwoPaperBank, captureSchedules = true)
        assertSameState()
        // The schedule landed on the question of the second paper too, not just
        // the previewed first one.
        assertEquals(2000L, streamingDb.cardStateDao().get("p2", "q3")!!.dueAt)
    }

    /**
     * A re-import skips the duplicate, so the file's stale schedule must not
     * roll back a card the user has since moved on with — the same rule the
     * whole-file importer follows.
     */
    @Test
    fun aReimportDoesNotRollBackAMovedOnCard() = runBlocking {
        wholeFileImport(wholeFileDb, scheduledTwoPaperBank)
        streamingImport(streamingDb, scheduledTwoPaperBank, captureSchedules = true)
        for (db in listOf(wholeFileDb, streamingDb)) {
            val moved = db.cardStateDao().get("p1", "q1")!!.copy(reps = 9, dueAt = 99999)
            db.cardStateDao().upsert(moved)
        }
        wholeFileImport(wholeFileDb, scheduledTwoPaperBank)
        streamingImport(streamingDb, scheduledTwoPaperBank, captureSchedules = true)
        assertSameState()
        assertEquals(99999L, wholeFileDb.cardStateDao().get("p1", "q1")!!.dueAt)
        assertEquals(99999L, streamingDb.cardStateDao().get("p1", "q1")!!.dueAt)
    }

    @Test
    fun progressIsMonotonicAndEndsAtTheTotal() = runBlocking {
        val n = 7
        val questions = (1..n).joinToString(",") {
            """{"id":"q$it","text":"Q$it","options":["a","b"],"correctOptionIds":["a"]}"""
        }
        val json = """{"papers":[{"id":"p","title":"P","categories":[{"id":"c","title":"C","questions":[$questions]}]}]}"""
        val seen = mutableListOf<Int>()
        StreamingImporter(streamingDb, batchSize = 2)
            .import({ ByteArrayInputStream(json.toByteArray()) }) { seen += it }
        assertEquals(listOf(2, 4, 6, 7), seen)
    }
}
