package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.QuestionOption
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

    @Test
    fun deletingAPaperDiscardsItsResumeSnapshot() = runBlocking {
        seedPaper("p1", "c1")
        seedPaper("p2", "c2")
        repository.saveTestProgress(
            TestSnapshot(paperId = "p1", categoryIds = listOf("c1"), questionIds = listOf("q-p1-1"))
                .toJson()
        )

        repository.deletePaper("p1")

        // Resuming would present an empty paper, so the snapshot has to go.
        assertNull(repository.loadTestProgress())
    }

    @Test
    fun deletingAPaperKeepsAnotherPapersSnapshot() = runBlocking {
        seedPaper("p1", "c1")
        seedPaper("p2", "c2")
        repository.saveTestProgress(
            TestSnapshot(paperId = "p2", categoryIds = listOf("c2"), questionIds = listOf("q-p2-1"))
                .toJson()
        )

        repository.deletePaper("p1")

        assertEquals(
            "p2",
            TestSnapshot.fromJson(repository.loadTestProgress()!!)?.paperId
        )
    }

    @Test
    fun anUnparseableSnapshotIsClearedRatherThanLeftToRot() = runBlocking {
        seedPaper("p1", "c1")
        repository.saveTestProgress("not json at all")

        repository.deletePaper("p1")

        assertNull(repository.loadTestProgress())
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
}
