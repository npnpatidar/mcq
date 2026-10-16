package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Mastery
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.QuestionStats
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The maintained `question_stats` aggregate must reproduce exactly what the
 * old History path computed in memory from every result row. This runs both
 * pipelines over the same attempts and asserts the two five-row lists match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryStatsParityTest {

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

    private fun question(id: String, correct: Set<String>, marks: Double = 1.0) = Question(
        id = id,
        categoryId = "c1",
        elements = listOf(ContentElement.TextElement("Q $id")),
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
        correctOptionIds = correct,
        marks = marks
    )

    private suspend fun attempt(
        finishedAt: Long,
        selections: Map<String, Set<String>>,
        questions: List<Question> = listOf(
            question("q1", setOf("a")),
            question("q2", setOf("a")),
            question("q3", setOf("a"))
        )
    ) = repository.saveAttempt(
        paperId = "p1",
        paperTitle = "Paper",
        questions = questions,
        selections = selections,
        negativeMarking = 0.0,
        durationSeconds = 30,
        finishedAt = finishedAt
    )

    @Test
    fun sqlAggregateMatchesTheInMemoryHistoryPipeline() = runBlocking {
        val questions = listOf(
            question("q1", setOf("a")),
            question("q2", setOf("a")),
            question("q3", setOf("a")),
            // No answer key: practice attempts must not rank as hardest.
            question("q4", emptySet())
        )
        attempt(
            1000L,
            mapOf("q1" to setOf("a"), "q2" to emptySet(), "q3" to setOf("a"), "q4" to setOf("a")),
            questions
        )
        attempt(
            2000L,
            mapOf("q1" to setOf("b"), "q2" to setOf("b"), "q3" to setOf("a"), "q4" to emptySet()),
            questions
        )

        val allResults = repository.getAllQuestionResults()
        val expectedHardest = QuestionStats.hardest(QuestionStats.aggregate(allResults))
        val expectedWeakest = Mastery.weakest(
            Mastery.perCategory(repository.getAttempts(), allResults)
        )

        val actualHardest = repository.getHardestQuestions()
        val actualWeakest = repository.getWeakestCategories()

        assertEquals(
            expectedHardest.map { it.questionId },
            actualHardest.map { it.questionId }
        )
        expectedHardest.zip(actualHardest).forEach { (expected, actual) ->
            assertEquals(expected.wrong, actual.wrong)
            assertEquals(expected.skipped, actual.skipped)
            assertEquals(expected.text, actual.text)
        }
        assertEquals(
            expectedWeakest.map { it.paperId to it.categoryTitle },
            actualWeakest.map { it.paperId to it.categoryTitle }
        )
        expectedWeakest.zip(actualWeakest).forEach { (expected, actual) ->
            assertEquals(expected.correct, actual.correct)
            assertEquals(expected.graded, actual.graded)
        }
    }
}
