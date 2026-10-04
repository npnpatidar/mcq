package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Drill
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import kotlinx.coroutines.flow.first
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
 * The drill dialog refuses a count the paper cannot supply, using
 * `Paper.totalQuestions` — so that count has to be the real number of questions
 * in the paper, nested categories included.
 *
 * `McqRepository.getPaper` maps a paper with an *empty* count map and therefore
 * reports zero, so this goes through `observePapers`, which is what the library
 * screen actually reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrillCountAvailabilityTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun question(id: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    @Test
    fun theLibrarySeesHowManyQuestionsAPaperActuallyHas() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "root", "Root")
        val childId = repository.addCategory("p1", "Child", parentId = "root")
        repository.saveQuestion(question("q1", "root"))
        repository.saveQuestion(question("q2", "root"))
        repository.saveQuestion(question("q3", childId))

        val paper = repository.observePapers().first().single { it.id == "p1" }
        assertEquals("the drill dialog would refuse a valid count if this is wrong", 3, paper.totalQuestions)
        assertTrue(Drill.canSample(3, paper.totalQuestions))
        assertFalse(Drill.canSample(4, paper.totalQuestions))
    }

    @Test
    fun aPaperWithNoQuestionsReportsZero() = runBlocking {
        repository.ensurePaperAndCategory("empty", "Empty", "c", "Cat")
        val paper = repository.observePapers().first().single { it.id == "empty" }
        assertEquals(0, paper.totalQuestions)
        assertEquals(Drill.CountCheck.NoQuestionsAvailable, Drill.checkCount(10, paper.totalQuestions))
    }

    @Test
    fun aDrillNeverRunsWithMoreQuestionsThanThePaperHolds() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c", "Cat")
        repository.saveQuestion(question("q1", "c"))
        repository.saveQuestion(question("q2", "c"))

        val available = repository.observePapers().first().single { it.id == "p1" }.totalQuestions
        // Whatever the UI let through, Drill.sample must not invent questions.
        val sampled = Drill.sample(repository.getQuestionsForPaper("p1"), 50, 7L)
        assertTrue(
            "a drill of 50 must not exceed the $available questions that exist",
            sampled.size <= available
        )
    }
}
