package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repository behavior against a real (in-memory) Room database under
 * Robolectric: grading weights, bookmark persistence and bulk duplication.
 * Pure scoring math lives in ScoringTest; this pins the DB wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

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

    private fun question(
        id: String,
        correct: Set<String>,
        marks: Double = 1.0
    ) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = correct,
        marks = marks
    )

    @Test
    fun bookmarkTogglePersists() = runBlocking {
        assertFalse(repository.isBookmarked("q1"))
        repository.toggleBookmark("q1")
        assertTrue(repository.isBookmarked("q1"))
        repository.toggleBookmark("q1")
        assertFalse(repository.isBookmarked("q1"))
    }

    @Test
    fun saveAttemptWeightsMarksAndExcludesUngraded() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a"), marks = 2.0))
        repository.saveQuestion(question("q2", setOf("q2-a"), marks = 3.0))
        repository.saveQuestion(question("q3", emptySet(), marks = 5.0))
        val questions = repository.getQuestionsForPaper("p1")
        assertEquals(3, questions.size)
        val attemptId = repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a"), "q2" to setOf("nope"), "q3" to setOf("x")),
            negativeMarking = 0.25,
            durationSeconds = 60,
            finishedAt = System.currentTimeMillis()
        )
        val attempt = repository.getAttempt(attemptId)!!
        assertEquals(1, attempt.correctCount)
        assertEquals(1, attempt.wrongCount)
        assertEquals(0, attempt.skippedCount)
        assertEquals(2.0 - 0.75, attempt.score, 0.0001)
        assertEquals(5.0, attempt.maxScore, 0.0001)
        val results = repository.getAttemptResults(attemptId)
        assertEquals(3, results.size)
    }

    @Test
    fun duplicateQuestionCopiesContentWithFreshId() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", setOf("q1-a"), marks = 2.0))
        val copyId = repository.duplicateQuestion("q1")
        assertEquals("q1-copy", copyId)
        val copy = repository.getQuestion(copyId!!)!!
        assertEquals("Q q1", copy.text)
        assertEquals(setOf("q1-a"), copy.correctOptionIds)
        assertEquals(2.0, copy.marks, 0.0001)
        assertEquals(2, repository.getQuestionsForPaper("p1").size)
    }
}
