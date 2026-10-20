package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestSnapshot
import kotlinx.coroutines.flow.first
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

/**
 * Deleting a paper must leave nothing of it behind.
 *
 * Only categories, questions, options and correct answers have a foreign key
 * to the paper, so those clear by cascade. Attempts and their question_results
 * carry only a paperId string, and bookmarks carry only a questionId, so
 * without explicit deletes the paper vanishes and its history, its SM-2 cards
 * and its bookmarks stay behind as rows nothing can reach — visible in History
 * under a title that no longer exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletePaperCascadeTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun question(id: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    private fun seedPaper(paperId: String, categoryId: String) = runBlocking {
        repository.ensurePaperAndCategory(paperId, "Paper $paperId", categoryId, "Cat")
        repository.saveQuestion(question("q-$paperId-1", categoryId))
        repository.saveQuestion(question("q-$paperId-2", categoryId))
    }

    private fun seedEverything() = runBlocking {
        seedPaper("p1", "c1")
        // A second paper that must survive untouched.
        seedPaper("p2", "c2")

        repository.toggleBookmark("q-p1-1")
        repository.toggleBookmark("q-p2-1")

        listOf("p1" to "c1", "p2" to "c2").forEach { (paperId, categoryId) ->
            val questions = repository.getQuestionsForPaper(paperId)
            repository.saveAttempt(
                paperId = paperId,
                paperTitle = "Paper $paperId",
                questions = questions,
                selections = questions.associate { it.id to setOf("${it.id}-a") },
                negativeMarking = 0.0,
                durationSeconds = 30,
                finishedAt = System.currentTimeMillis()
            )
            repository.recordStudyReview(paperId, "q-$paperId-1", ReviewGrade.GOOD)
        }
    }

    @Test
    fun deletingAPaperRemovesItsHistory() = runBlocking {
        seedEverything()
        assertEquals(1, db.attemptDao().countByPaper("p1"))

        repository.deletePaper("p1")

        assertEquals("history for the deleted paper survived", 0, db.attemptDao().countByPaper("p1"))
        assertEquals(
            "question_results for the deleted paper survived",
            0,
            db.attemptDao().getAllResults().count { it.questionId.startsWith("q-p1-") }
        )
    }

    @Test
    fun deletingAPaperRemovesItsSchedulingCards() = runBlocking {
        seedEverything()
        assertTrue(db.cardStateDao().getByPaper("p1").isNotEmpty())

        repository.deletePaper("p1")

        assertTrue(
            "SM-2 cards for the deleted paper survived",
            db.cardStateDao().getByPaper("p1").isEmpty()
        )
    }

    @Test
    fun deletingAPaperRemovesItsBookmarksButNotOtherPapers() = runBlocking {
        seedEverything()

        repository.deletePaper("p1")

        // Only p1's bookmark goes; p2's must survive the delete.
        assertEquals(listOf("q-p2-1"), repository.observeBookmarks().first())
        // The untouched paper keeps its questions and its own schedule.
        assertTrue(db.cardStateDao().getByPaper("p2").isNotEmpty())
        assertNull("a deleted paper's question is still readable", repository.getQuestion("q-p1-1"))
        assertTrue("the surviving paper lost a question", repository.getQuestion("q-p2-1") != null)
    }

    @Test
    fun deletingOnePaperLeavesTheOthersHistoryIntact() = runBlocking {
        seedEverything()
        assertEquals(2, db.attemptDao().getAllAttempts().size)

        repository.deletePaper("p1")

        assertEquals(1, db.attemptDao().getAllAttempts().size)
        assertEquals(0, db.attemptDao().countByPaper("p1"))
        assertEquals(1, db.attemptDao().countByPaper("p2"))
    }

    /**
     * `question_stats` has no foreign key to questions (its PK is the question
     * id alone), so the orphan sweeps that already cover `card_state` and
     * `review_log` stopped one table short: the lifetime aggregate outlived
     * its question, and since History's hardest/weakest reads go straight to
     * the aggregate without joining the question rows, deleted questions kept
     * ranking and a deleted paper's (paper, category) buckets kept counting
     * forever — for a lifetime view that is supposed to be the honest one.
     * Every delete site now sweeps the aggregate the same way it sweeps the
     * other two tables.
     */
    @Test
    fun deletedQuestionsAndPapersStopRankingInTheHistoryAggregates() = runBlocking {
        seedPaper("p1", "c1")
        seedPaper("p2", "c2")
        seedPaper("p3", "c3")
        listOf("p1", "p2", "p3").forEach { paperId ->
            val questions = repository.getQuestionsForPaper(paperId)
            repository.saveAttempt(
                paperId = paperId,
                paperTitle = "Paper $paperId",
                questions = questions,
                // Wrong on purpose: the question has to rank in "hardest".
                selections = questions.associate { it.id to setOf("${it.id}-b") },
                negativeMarking = 0.0,
                durationSeconds = 30,
                finishedAt = System.currentTimeMillis()
            )
        }
        // Sanity: all six rank while everything is alive, and each paper owns
        // one weakest-categories bucket.
        assertEquals(6, repository.getHardestQuestions(limit = 10).size)
        assertEquals(
            setOf("p1", "p2", "p3"),
            repository.getWeakestCategories(limit = 10).map { it.paperId }.toSet()
        )

        // A deleted question leaves the ranking at its own delete site...
        repository.deleteQuestion("q-p1-1")
        assertTrue(
            "the deleted question still ranks in hardest",
            repository.getHardestQuestions(limit = 10).none { it.questionId == "q-p1-1" }
        )
        // ...and the bulk delete sweeps each id the same way.
        repository.deleteQuestions(listOf("q-p1-2"))
        assertTrue(
            "the bulk delete left the aggregated question ranking",
            repository.getHardestQuestions(limit = 10).none { it.questionId == "q-p1-2" }
        )

        // A deleted paper's aggregates go with the paper: no ranking, no
        // weakest-categories bucket.
        repository.deletePaper("p2")
        assertTrue(
            "the deleted paper's question still ranks in hardest",
            repository.getHardestQuestions(limit = 10).none { it.questionId.startsWith("q-p2-") }
        )
        assertTrue(
            "the deleted paper still owns a weakest-categories bucket",
            repository.getWeakestCategories(limit = 10).none { it.paperId == "p2" }
        )

        // A deleted category's questions leave the ranking with their cascade.
        repository.deleteCategory("c3")
        assertTrue(
            "the deleted category's question still ranks in hardest",
            repository.getHardestQuestions(limit = 10).none { it.questionId.startsWith("q-p3-") }
        )
    }

    @Test
    fun deletingAPaperDiscardsItsResumeSnapshot() = runBlocking {
        seedPaper("p1", "c1")
        seedPaper("p2", "c2")
        repository.saveTestProgress(
            TestSnapshot(paperId = "p1", categoryIds = listOf("c1"), questionIds = listOf("q-p1-1")),
            listOf(TestAnswer(questionId = "q-p1-1", selection = setOf("q-p1-1-a")))
        )

        repository.deletePaper("p1")

        // Resuming would present an empty paper, so the snapshot has to go: the
        // header by hand (one global slot) and the answers with the paper row.
        assertNull(repository.loadTestSnapshot())
        assertTrue(db.testProgressDao().getForPaper("p1").isEmpty())
    }

    @Test
    fun deletingAPaperKeepsAnotherPapersSnapshot() = runBlocking {
        seedPaper("p1", "c1")
        seedPaper("p2", "c2")
        repository.saveTestProgress(
            TestSnapshot(paperId = "p2", categoryIds = listOf("c2"), questionIds = listOf("q-p2-1")),
            listOf(TestAnswer(questionId = "q-p2-1", selection = setOf("q-p2-1-a")))
        )

        repository.deletePaper("p1")

        assertEquals("p2", repository.loadTestSnapshot()?.paperId)
        assertEquals(
            "the surviving session keeps its answers",
            listOf("q-p2-1"),
            repository.resumeOffer("p2")!!.selections.keys.toList()
        )
    }

    @Test
    fun anUnparseableSnapshotIsClearedRatherThanLeftToRot() = runBlocking {
        seedPaper("p1", "c1")
        repository.restoreTestProgress("not json at all")

        // Reading it is what discards it — leaving it would mean re-parsing a
        // dead document on every load of the screen.
        assertNull(repository.loadTestSnapshot())

        repository.deletePaper("p1")

        assertNull(repository.loadTestSnapshot())
    }

    @Test
    fun historyOfASurvivingPaperStillResolvesItsResults() = runBlocking {
        seedEverything()

        repository.deletePaper("p1")

        val remaining = db.attemptDao().getAllAttempts().single()
        assertEquals("p2", remaining.paperId)
        assertEquals(
            "results for the surviving attempt went missing",
            2,
            db.attemptDao().getResults(remaining.id).size
        )
        assertEquals(listOf("q-p2-1"), repository.observeBookmarks().first())
    }

    /**
     * Writing a paper or CATEGORY whose id ALREADY exists must not rebuild
     * the row with a DELETE: `INSERT OR REPLACE` is a delete-then-insert and
     * Room fires the declared foreign-key cascades on the delete half. This
     * test therefore drives BOTH halves of ensurePaperAndCategory, and both
     * are LATENT today — the only caller is the unused
     * McqRepository.ensurePaperAndCategory wrapper, nothing in main's UI
     * reaches it — which is why the stores are driven DIRECTLY, as the
     * report specified:
     *
     * - the paper half: the first write-back (savePaper) guards an already-
     *   existing paper row (paper-<millis> ids are fresh and no edit-paper
     *   flow exists);
     * - the category half — proven by the SECOND ensure call: CategoryDao
     *   kept a REPLACE upsert, so re-writing the existing category row
     *   deleted it and the cascade took its questions, options, answer keys
     *   and card schedules; a REPLACE also zeroed its sortOrder (part of
     *   the same bug). The identity update deliberately excludes
     *   parentId/sortOrder; the editor owns that ordering.
     *
     * Room/SQLite — CI is the authority. Mutant: restore the REPLACE
     * upserts (paper or category, either alone) — the child counts below
     * go red: the paper mutant erases everything under p1, the category
     * mutant erases the questions under c1 (and the cards with them).
     */
    @Test
    fun resavingAPaperNeverErasesItsChildren() = runBlocking {
        seedEverything()
        repository.saveTestProgress(
            TestSnapshot(
                paperId = "p1",
                categoryIds = listOf("c1"),
                questionIds = listOf("q-p1-1", "q-p1-2")
            ),
            changed = emptyList()
        )
        val categoriesBefore = db.categoryDao().getByPaper("p1").size
        val questionsBefore = db.questionDao().countByPaper("p1")
        val cardsBefore = db.cardStateDao().countByPaper("p1")
        val attemptsBefore = db.attemptDao().countByPaper("p1")
        val sortOrderBefore = db.categoryDao().getById("c1")?.sortOrder
        assertTrue(categoriesBefore > 0 && questionsBefore > 0 && cardsBefore > 0)

        // The same pair of ids, written again through the store APIs: the
        // savePaper round covers the paper row, and the ensurePaperAndCategory
        // call BELOW is what exercises the category row (the same ids as the
        // seed, so its REPLACE history would delete the row in place).
        repository.savePaper(
            com.mcqapp.domain.Paper(
                id = "p1",
                title = "Paper p1 retitled",
                description = "again",
                durationMinutes = 45
            )
        )
        repository.ensurePaperAndCategory("p1", "Paper re-ensured", "c1", "Cat")

        assertEquals("the category survived the re-save", categoriesBefore, db.categoryDao().getByPaper("p1").size)
        assertEquals(
            "the re-ensure kept the row the editor ordered",
            sortOrderBefore, db.categoryDao().getById("c1")?.sortOrder
        )
        assertEquals("the questions survived the re-save", questionsBefore, db.questionDao().countByPaper("p1"))
        assertEquals("the card schedules survived the re-save", cardsBefore, db.cardStateDao().countByPaper("p1"))
        assertEquals("the attempt history survived the re-save", attemptsBefore, db.attemptDao().countByPaper("p1"))
        assertEquals(
            "the resume header survived the re-save",
            "p1",
            repository.loadTestSnapshot()?.paperId
        )
        val paper = db.paperDao().getById("p1")
        assertEquals("the second write's title landed", "Paper re-ensured", paper?.title)
    }
}
