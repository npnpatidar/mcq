package com.mcqapp

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.AttemptEntity
import com.mcqapp.data.local.QuestionResultEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Passage
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewFilters
import com.mcqapp.domain.TestAnswer
import com.mcqapp.domain.TestSnapshot
import com.mcqapp.ui.common.unfinishedTestRoute
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two database reads the wave-1 UX findings depend on: a result row's
 * passage resolved live by question id, and the single saved session surfaced
 * as a card's "N answered". Robolectric needs real SQLite: CI (x86_64) only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestUxDbTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = McqRepository(db, context)
        runBlocking { repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat") }
    }

    @After
    fun tearDown() {
        runBlocking { repository.clearTestProgress() }
        db.close()
    }

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id",
        options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
        correctOptionIds = setOf("a")
    )

    @Test
    fun passageIsResolvedByQuestionIdWithoutAnyResultSchemaField() = runBlocking {
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
        val passageId = repository.savePassage(Passage(id = "", categoryId = "c1", title = "Source"))
        repository.assignQuestionsToPassage(listOf("q1"), passageId)

        val found = repository.passagesForQuestionIds(listOf("q1", "q2"))
        assertEquals("Source", found["q1"]?.title)
        assertNull("a question outside the passage gets no passage", found["q2"])
    }

    @Test
    fun resultPageCarriesTheLivePassageAboveTheStem() = runBlocking {
        repository.saveQuestion(question("q1"))
        val passageId = repository.savePassage(Passage(id = "", categoryId = "c1", title = "Context"))
        repository.assignQuestionsToPassage(listOf("q1"), passageId)
        val attemptId = insertAttempt()

        val rows = walkPaging(10, { repository.resultPagingSource(attemptId, ReviewFilters.ALL) { emptySet() } }) {
            PagingSource.LoadParams.Refresh(it, 10, false)
        }
        assertEquals(1, rows.size)
        assertEquals("Context", rows.single().result.passage?.title)
    }

    private suspend fun insertAttempt(): Long = db.attemptDao().insertAttempt(
        AttemptEntity(
            paperId = "p1",
            title = "Paper",
            totalQuestions = 1,
            correctCount = 1,
            wrongCount = 0,
            skippedCount = 0,
            score = 1.0,
            maxScore = 1.0,
            durationSeconds = 0,
            finishedAt = 1000L
        )
    ).also { attemptId ->
        db.attemptDao().insertResults(
            listOf(
                QuestionResultEntity(
                    attemptId = attemptId,
                    questionId = "q1",
                    categoryTitle = "Cat",
                    text = "Q q1",
                    optionsJson = "[]",
                    correctOptionIds = "a",
                    selectedOptionIds = "a",
                    isCorrect = true,
                    explanation = ""
                )
            )
        )
    }

    @Test
    fun unfinishedTestRouteCarriesTheExactSessionIdentity() {
        val saved = com.mcqapp.domain.UnfinishedTest(
            snapshot = TestSnapshot(
                paperId = "paper-1",
                categoryIds = listOf("c1", "c2"),
                currentIndex = 12,
                testType = "BLUEPRINT",
                requestedCount = 40,
                exclusionMode = "RECENT_3",
                variant = "77",
                isDrill = true,
                drillRequested = 30,
                selectedQuestionIds = listOf("q1", "q2")
            ),
            answeredCount = 12
        )
        val route = unfinishedTestRoute(saved)
        assertTrue(route.contains("paperId=paper-1"))
        assertTrue(route.contains("type=BLUEPRINT"))
        assertTrue(route.contains("count=40"))
        assertTrue(route.contains("exclusion=RECENT_3"))
        assertTrue(route.contains("variant=77"))
        assertTrue(route.contains("drillCount=30"))
        assertTrue(route.contains("categories="))
        assertFalse(route.contains("q1"))
    }

    @Test
    fun unfinishedTestCountsAnsweredQuestionsAndClearsWithTheSlot() = runBlocking {
        assertNull("no saved session means no card", repository.unfinishedTest())
        repository.saveTestProgress(
            TestSnapshot(
                paperId = "p1",
                categoryIds = listOf("c1"),
                currentIndex = 3,
                testType = "RANDOM",
                requestedCount = 20,
                totalSeconds = 600
            ),
            listOf(
                TestAnswer("q1", selection = setOf("a")),
                TestAnswer("q2", selection = setOf("b")),
                TestAnswer("q3", selection = emptySet())
            )
        )
        val saved = repository.unfinishedTest()
        assertNotNull(saved)
        assertEquals(2, saved!!.answeredCount)
        assertEquals(3, saved.snapshot.currentIndex)
        assertTrue("the route can rebuild the exact type", saved.snapshot.testType == "RANDOM")

        repository.clearTestProgress()
        assertNull(repository.unfinishedTest())
    }
}
