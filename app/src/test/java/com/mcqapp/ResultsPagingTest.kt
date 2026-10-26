package com.mcqapp

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.AttemptEntity
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.data.repository.ResultPageKey
import com.mcqapp.domain.NumberedResult
import com.mcqapp.domain.ReviewFilters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Results pages one attempt's rows instead of mapping all of them at once.
 * These pin the numbering contract the screen relies on: a row keeps its
 * index in the **unfiltered** attempt even when a chip filter hides rows, and
 * every chip selects exactly the rows `ReviewFilters` used to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResultsPagingTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() = db.close()

    private fun attempt(): Long = runBlocking {
        db.attemptDao().insertAttempt(
            AttemptEntity(
                paperId = "p1",
                title = "Paper",
                totalQuestions = 0,
                correctCount = 0,
                wrongCount = 0,
                skippedCount = 0,
                score = 0.0,
                maxScore = 0.0,
                durationSeconds = 0,
                finishedAt = 0L
            )
        )
    }

    private fun saveResults(attemptId: Long, vararg entities: QuestionResultEntity) = runBlocking {
        db.attemptDao().insertResults(entities.map { it.copy(attemptId = attemptId) })
    }

    private fun result(
        questionId: String,
        correct: Boolean,
        graded: Boolean,
        selected: Boolean = graded
    ) = QuestionResultEntity(
        attemptId = 0,
        questionId = questionId,
        categoryTitle = "Cat",
        text = "Q $questionId",
        optionsJson = "[]",
        correctOptionIds = if (graded) "a" else "",
        selectedOptionIds = if (selected) "a" else "",
        isCorrect = correct,
        explanation = ""
    )

    private suspend fun walk(
        factory: suspend () -> PagingSource<ResultPageKey, NumberedResult>
    ): List<NumberedResult> =
        walkPaging(2, factory) { key ->
            PagingSource.LoadParams.Refresh(key, 2, false)
        }

    @Test
    fun theWholeAttemptKeepsOrderAndIndexes() = runBlocking {
        val id = attempt()
        saveResults(
            id,
            result("q1", correct = true, graded = true),
            result("q2", correct = false, graded = true),
            result("q3", correct = false, graded = true),
            result("q4", correct = false, graded = false),
            result("q5", correct = true, graded = true)
        )
        val rows = walk { repository.resultPagingSource(id, ReviewFilters.ALL) { emptySet() } }
        assertEquals(listOf("q1", "q2", "q3", "q4", "q5"), rows.map { it.result.questionId })
        assertEquals(listOf(0, 1, 2, 3, 4), rows.map { it.index })
    }

    @Test
    fun aChipKeepsTheUnfilteredIndex() = runBlocking {
        val id = attempt()
        saveResults(
            id,
            result("q1", correct = true, graded = true),
            result("q2", correct = false, graded = true),
            result("q3", correct = false, graded = true, selected = false),
            result("q4", correct = false, graded = false, selected = false),
            result("q5", correct = true, graded = true)
        )
        val correct = walk { repository.resultPagingSource(id, ReviewFilters.CORRECT) { emptySet() } }
        assertEquals(listOf("q1", "q5"), correct.map { it.result.questionId })
        assertEquals(listOf(0, 4), correct.map { it.index })

        val wrong = walk { repository.resultPagingSource(id, ReviewFilters.WRONG) { emptySet() } }
        assertEquals(listOf("q2"), wrong.map { it.result.questionId })
        assertEquals(listOf(1), wrong.map { it.index })

        val skipped = walk { repository.resultPagingSource(id, ReviewFilters.SKIPPED) { emptySet() } }
        assertEquals(listOf("q3"), skipped.map { it.result.questionId })
        assertEquals(listOf(2), skipped.map { it.index })

        val ungraded = walk { repository.resultPagingSource(id, ReviewFilters.UNGRADED) { emptySet() } }
        assertEquals(listOf("q4"), ungraded.map { it.result.questionId })
        assertEquals(listOf(3), ungraded.map { it.index })

        val saved = walk {
            repository.resultPagingSource(id, ReviewFilters.SAVED) { setOf("q5") }
        }
        assertEquals(listOf("q5"), saved.map { it.result.questionId })
        assertEquals(listOf(4), saved.map { it.index })
    }

    @Test
    fun headerAggregatesMatchTheRows() = runBlocking {
        val id = attempt()
        saveResults(
            id,
            result("q1", correct = true, graded = true),
            result("q2", correct = false, graded = true),
            result("q3", correct = false, graded = false, selected = false)
        )
        val aggregates = repository.getResultAggregates(id)
        assertEquals(3, aggregates.total)
        assertEquals(1, aggregates.ungraded)
    }
}
