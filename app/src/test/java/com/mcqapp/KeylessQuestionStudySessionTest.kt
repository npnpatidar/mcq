package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A multiple-choice question with **no answer key** (an ungraded import) through
 * the real ViewModel. The app cannot know the answer, so it must never be
 * auto-graded: Check is refused, Show answer then the four self-grades is the
 * path, and the stored row carries no correctness or guess. A keyed MCQ is
 * pinned beside it so the rule cannot swallow normal grading.
 *
 * Robolectric + the real repository + the real database, so like every DB-backed
 * test it runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class KeylessQuestionStudySessionTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @get:Rule
    val settingsReset = SettingsResetRule()

    @After
    fun cleanup() = runBlocking {
        repository.resetSchedulerConfig()
        repository.setSimplifiedStudy(true)
        repository.deletePaper("p1")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /** Two options, but nobody knows which is right: an ungraded import. */
    private fun keyless(id: String = "nokey1", categoryId: String = "c1") = Question(
        id = id,
        categoryId = categoryId,
        elements = listOf(ContentElement.TextElement("Prompt $id")),
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = emptySet(),
        explanationElements = listOf(ContentElement.TextElement("Because $id"))
    )

    private fun keyed(id: String = "keyed1", categoryId: String = "c1") = Question(
        id = id,
        categoryId = categoryId,
        elements = listOf(ContentElement.TextElement("Prompt $id")),
        options = listOf(QuestionOption("$id-a", "Alpha"), QuestionOption("$id-b", "Beta")),
        correctOptionIds = setOf("$id-a")
    )

    private fun open(question: Question): StudyViewModel {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question)
        }
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(
            "state=${viewModel.state.value}",
            awaitCondition { viewModel.state.value.current != null }
        )
        assertEquals(question.id, viewModel.state.value.current?.questionId)
        return viewModel
    }

    private suspend fun reviews(id: String): List<ReviewLogEntity> =
        repository.db().reviewLogDao().historyForQuestion(id)

    private fun assertSelfGradedRow(row: ReviewLogEntity, rating: Int) {
        assertEquals("the chosen grade is the stored rating", rating, row.rating)
        assertNull("a keyless question has no correct answer to record", row.wasCorrect)
        assertNull("a keyless question is never a declared guess", row.wasGuess)
        assertEquals("nothing was skipped: the prompt was studied", false, row.wasSkipped)
        assertNull("the app proposed no grade", row.proposedRating)
        assertEquals("the learner chose the grade", "CHOSEN", row.gradeSource)
    }

    @Test
    fun checkCannotAutoGradeAKeylessQuestion() {
        val viewModel = open(keyless())
        viewModel.toggleOption("nokey1-a")

        // Without the key guard this proposes AGAIN (selection != empty key) and
        // shows a red "Incorrect" for an answer nobody knows.
        viewModel.check()

        val state = viewModel.state.value
        assertFalse("Check must not reveal a keyless question", state.revealed)
        assertNull("Check must not propose a grade", state.pendingGrade)
        assertNull("there is no result card to build", state.lastResult)
        assertEquals("nothing may be persisted", 0, runBlocking { reviews("nokey1") }.size)
    }

    @Test
    fun aKeylessGradeWritesTheSelfGradedRow() {
        val viewModel = open(keyless())
        assertTrue("simplified is the default", viewModel.state.value.simplified)
        viewModel.reveal()
        viewModel.grade(ReviewGrade.GOOD)

        assertTrue("the grade must persist", awaitCondition {
            runBlocking { reviews("nokey1").size } == 1
        })
        assertSelfGradedRow(runBlocking { reviews("nokey1") }.single(), rating = 3)
        assertEquals(
            "the sitting must count the self-graded question",
            1,
            viewModel.state.value.selfGradedReviewed
        )
    }

    @Test
    fun aClassicModeKeylessGradeWritesTheSameRow() {
        runBlocking { repository.setSimplifiedStudy(false) }
        val viewModel = open(keyless())
        assertFalse("classic mode must be on", viewModel.state.value.simplified)

        viewModel.reveal()
        viewModel.grade(ReviewGrade.EASY)

        assertTrue("the grade must persist", awaitCondition {
            runBlocking { reviews("nokey1").size } == 1
        })
        assertSelfGradedRow(runBlocking { reviews("nokey1") }.single(), rating = 4)
    }

    @Test
    fun aGuessIsRefusedForKeylessQuestions() {
        val viewModel = open(keyless())
        viewModel.setGuess(true)
        assertFalse(
            "a question the app cannot check has nothing to declare a guess about",
            viewModel.state.value.isGuess
        )
    }

    @Test
    fun aKeyedQuestionStillAutoGrades() {
        // The negative side of the rule: a known key keeps the old path — Check
        // proposes, Next persists a correct AUTO row.
        val viewModel = open(keyed())
        viewModel.toggleOption("keyed1-a")
        viewModel.check()

        assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
        assertEquals(true, viewModel.state.value.lastResult?.correct)
        viewModel.next()

        assertTrue("the grade must persist", awaitCondition {
            runBlocking { reviews("keyed1").size } == 1
        })
        val row = runBlocking { reviews("keyed1") }.single()
        assertEquals(3, row.rating)
        assertEquals("the known key records the answer as right", true, row.wasCorrect)
        assertEquals("the guess switch was off", false, row.wasGuess)
        assertEquals("the question was answered", false, row.wasSkipped)
        assertEquals("the app proposed the grade", 3, row.proposedRating)
        assertEquals("the app's proposal was saved unchanged", "AUTO", row.gradeSource)
        assertEquals(
            "an auto-graded question is not a self-grade",
            0,
            viewModel.state.value.selfGradedReviewed
        )
    }
}
