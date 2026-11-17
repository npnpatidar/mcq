package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.io.AnkiApkgImporter
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.StreamingImporter
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.domain.CardStateKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * A failed streaming import must leave the library exactly as it was. Both the
 * JSON and the `.apkg` reader write in batches, so the failure is forced after
 * batch 1 via a throwing [onProgress] — the point where the old per-batch
 * commits had already made rows visible. The assertion is a byte-for-byte dump
 * of every table the import can touch, taken before and after: no created row
 * may remain, and a pre-existing paper the run matched by id (and therefore
 * updated) must come back with its original exam settings.
 *
 * Robolectric + a real in-memory Room database: these are the writes under
 * test. CI on x86_64 is authoritative; see AGENTS.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportRollbackTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    /** Every cell of every table the import path can write, in a stable order. */
    private fun dump(): List<String> {
        val raw = db.openHelper.writableDatabase
        val tables = listOf(
            "papers", "categories", "questions", "options", "correct_answers",
            "question_tags", "passages", "card_state", "bookmarks", "attempts",
            "question_results", "question_stats", "review_log"
        )
        val out = mutableListOf<String>()
        for (table in tables) {
            raw.query("SELECT * FROM `$table`").use { c ->
                val names = c.columnNames
                while (c.moveToNext()) {
                    val cells = names.indices.joinToString(",") { i ->
                        names[i] + "=" + c.getString(i)
                    }
                    out += "$table: $cells"
                }
            }
        }
        return out.sorted()
    }

    @Test
    fun failedStreamedJsonRollsBackEveryBatchAndRestoresPreexistingPaper() {
        // The file's paper id matches this stored paper, so a healthy run would
        // overwrite these fields — the rollback must put every one of them back.
        runBlocking {
            db.paperDao().insertIgnore(
                PaperEntity(
                    id = "p1",
                    title = "Original title",
                    description = "Original description",
                    durationMinutes = 60,
                    negativeMarking = 0.5,
                    sortOrder = 7L
                )
            )
        }
        val before = dump()

        val json = """
        {
          "version": 2,
          "papers": [
            {
              "id": "p1",
              "title": "Replaced title",
              "description": "Replaced description",
              "duration": 30,
              "negative": 0.1,
              "categories": [
                {"id": "c-new", "title": "New category", "questions": [
                  {"id": "q-new-1", "text": "First new question?", "options": ["a", "b"], "correctOptionIds": ["a"]},
                  {"id": "q-new-2", "text": "Second new question?", "options": ["a", "b"], "correctOptionIds": ["b"]}
                ]}
              ]
            }
          ]
        }
        """.trimIndent()

        val propagated = try {
            runBlocking {
                StreamingImporter(db, batchSize = 1).import(
                    source = { ByteArrayInputStream(json.toByteArray()) }
                ) {
                    // Fails after batch 1 has already been written.
                    throw IllegalStateException("boom after batch 1")
                }
            }
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue("the consumer's failure must propagate", propagated)
        assertEquals(
            "a failed streamed JSON import must leave the library byte-for-byte unchanged",
            before,
            dump()
        )
    }

    @Test
    fun failedAnkiImportRollsBackEveryBatchAndRestoresPreexistingPaper() {
        val paper = PaperDto(
            id = "deck-1",
            title = "Deck",
            categories = listOf(
                CategoryDto(
                    id = "c1",
                    title = "C",
                    questions = listOf(
                        QuestionDto(
                            id = "aq1", text = "Anki question one?",
                            options = listOf(OptionDto("a", "A"), OptionDto("b", "B")),
                            correctOptionIds = listOf("a")
                        ),
                        QuestionDto(
                            id = "aq2", text = "Anki question two?",
                            options = listOf(OptionDto("a", "A"), OptionDto("b", "B")),
                            correctOptionIds = listOf("b")
                        )
                    )
                )
            )
        )
        val apkg = apkgOf(paper)
        // The reader derives the paper id from the deck; find it from a pure
        // read so the seeded paper is the one the import will match by id.
        val deckPaperId = AnkiPackageReader.read(apkg).file.papers.first().id
        runBlocking {
            db.paperDao().insertIgnore(
                PaperEntity(
                    id = deckPaperId,
                    title = "Kept title",
                    description = "Kept description",
                    durationMinutes = 99,
                    negativeMarking = 0.75,
                    sortOrder = 3L
                )
            )
        }
        val before = dump()

        val propagated = try {
            runBlocking {
                AnkiApkgImporter(db, batchSize = 1).import(
                    source = { ByteArrayInputStream(apkg) },
                    // Fails after batch 1 has already been written. Passed by
                    // name: the signature's last parameter is nowMillis: Long,
                    // so a trailing lambda would bind to it, not onProgress.
                    onProgress = { throw IllegalStateException("boom after batch 1") }
                )
            }
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue("the consumer's failure must propagate", propagated)
        assertEquals(
            "a failed .apkg import must leave the library byte-for-byte unchanged",
            before,
            dump()
        )
    }

    /**
     * The other writer a failed run must undo is the duplicate-refresh path:
     * with the toggle on it rewrites a pre-existing row's answer key and drops
     * its card_state (the learner studied a different key). Seed q1 with key
     * "a" and a real schedule, then stream the same question under q2 with key
     * "b" — the refresh lands in batch 1 before the throwing onProgress, and
     * the whole-run rollback must restore both the key and the schedule.
     */
    @Test
    fun failedStreamedDuplicateRefreshRestoresAnswerKeyAndCardState() {
        val seed = McqFileDto(
            version = 1,
            papers = listOf(
                PaperDto(
                    id = "p1", title = "Paper",
                    categories = listOf(
                        CategoryDto(
                            id = "c1", title = "Cat",
                            questions = listOf(
                                QuestionDto(
                                    id = "q1", text = "2 + 2?",
                                    options = listOf(OptionDto("a", "a"), OptionDto("b", "b")),
                                    correctOptionIds = listOf("a")
                                )
                            )
                        )
                    )
                )
            )
        )
        runBlocking {
            Importer(db).import(seed)
            db.cardStateDao().upsert(
                CardStateEntity(
                    paperId = "p1", questionId = "q1",
                    ease = 2.5, intervalDays = 12, dueAt = 9_000L, reps = 4,
                    lapses = 1, leech = false, lastReviewedAt = 8_000L,
                    contentHash = db.questionDao().getById("q1")!!.contentHash,
                    difficulty = 6.2, stability = 11.5, stabilityFast = 3.5,
                    cardState = CardStateKind.REVIEW, reviewCount = 4
                )
            )
        }
        val keyBefore = runBlocking { db.correctAnswerDao().getCorrectIds("q1") }
        val cardBefore = runBlocking { db.cardStateDao().get("p1", "q1") }
        val before = dump()

        val json = """
        {
          "version": 2,
          "papers": [
            {
              "id": "p1",
              "title": "Paper",
              "categories": [
                {"id": "c1", "title": "Cat", "questions": [
                  {"id": "q2", "text": "2 + 2?", "options": ["a", "b"], "correctOptionIds": ["b"]},
                  {"id": "q3", "text": "3 + 3?", "options": ["a", "b"], "correctOptionIds": ["a"]}
                ]}
              ]
            }
          ]
        }
        """.trimIndent()

        val propagated = try {
            runBlocking {
                StreamingImporter(db, batchSize = 1, updateAnswersOnDuplicate = true).import(
                    source = { ByteArrayInputStream(json.toByteArray()) }
                ) {
                    // Fails after batch 1 (the refresh of q1) was written.
                    throw IllegalStateException("boom after batch 1")
                }
            }
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue("the consumer's failure must propagate", propagated)
        assertEquals(
            "the refreshed answer key must be rolled back byte-for-byte",
            keyBefore,
            runBlocking { db.correctAnswerDao().getCorrectIds("q1") }
        )
        assertEquals(
            "the deleted card_state must be restored byte-for-byte",
            cardBefore,
            runBlocking { db.cardStateDao().get("p1", "q1") }
        )
        assertEquals(
            "a failed duplicate-refresh import must leave the library byte-for-byte unchanged",
            before,
            dump()
        )

        // Prove the fixture really exercises the refresh path: the same run
        // without the forced failure must refresh q1's key and drop its card.
        runBlocking {
            StreamingImporter(db, batchSize = 1, updateAnswersOnDuplicate = true).import(
                source = { ByteArrayInputStream(json.toByteArray()) }
            )
            assertEquals(listOf("b"), db.correctAnswerDao().getCorrectIds("q1"))
            assertNull(db.cardStateDao().get("p1", "q1"))
        }
    }
}
