package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
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
 * Study behaviour for flashcards through the real ViewModel: Check cannot
 * auto-grade a card, a grade writes the honest self-graded row, a guess cannot
 * be declared, and Stop records a sitting only when a card was actually graded.
 *
 * Robolectric + the real repository + the real database, so like every DB-backed
 * test it runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class FlashcardStudySessionTest {

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

    /** A zero-option question: the stem is the front, the explanation the back. */
    private fun flashcard(id: String = "card1", categoryId: String = "c1") = Question(
        id = id,
        categoryId = categoryId,
        elements = listOf(ContentElement.TextElement("Prompt $id")),
        options = emptyList(),
        correctOptionIds = emptySet(),
        explanationElements = listOf(ContentElement.TextElement("Answer $id"))
    )

    private fun seedCard(): StudyViewModel {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(flashcard())
        }
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(
            "state=${viewModel.state.value}",
            awaitCondition { viewModel.state.value.current != null }
        )
        assertTrue("the pulled question must be a card", viewModel.state.value.isFlashcard)
        return viewModel
    }

    /** Two fresh cards, so a failed pull can be retried and still find work. */
    private fun seedTwoCards(): StudyViewModel {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(flashcard("card1"))
            repository.saveQuestion(flashcard("card2"))
        }
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(
            "state=${viewModel.state.value}",
            awaitCondition { viewModel.state.value.current != null }
        )
        return viewModel
    }

    private suspend fun reviews(id: String = "card1"): List<ReviewLogEntity> =
        repository.db().reviewLogDao().historyForQuestion(id)

    private suspend fun sessionRows(): Int =
        repository.db().studySessionDao().getAll().size

    @Test
    fun checkCannotAutoGradeACard() {
        val viewModel = seedCard()

        // The footer shows Show answer instead; this is the defence behind it.
        // Without the guard, autoGrade reads the empty selection as wrong and
        // proposes AGAIN.
        viewModel.check()

        val state = viewModel.state.value
        assertFalse("Check must not reveal a card", state.revealed)
        assertNull("Check must not propose a grade for a card", state.pendingGrade)
        assertNull("a card has no result card to build", state.lastResult)
        assertEquals("nothing may be persisted", 0, runBlocking { reviews().size })
    }

    @Test
    fun gradeWritesTheCardRowAsSelfGraded() {
        val viewModel = seedCard()
        viewModel.reveal()
        viewModel.grade(ReviewGrade.GOOD)

        assertTrue("the grade must persist", awaitCondition {
            runBlocking { reviews().size } == 1
        })
        val row = runBlocking { reviews() }.single()
        assertEquals("the chosen grade is the stored rating", 3, row.rating)
        assertNull("a card has no correct answer to record", row.wasCorrect)
        assertNull("a card is never a declared guess", row.wasGuess)
        assertEquals("nothing was skipped: the prompt was studied", false, row.wasSkipped)
        assertNull("the app proposed no grade for a card", row.proposedRating)
        assertEquals("the learner chose the grade", "CHOSEN", row.gradeSource)
        assertEquals(
            "the sitting must count the card it graded",
            1,
            viewModel.state.value.flashcardsReviewed
        )
    }

    @Test
    fun gradingACardBeforeTheRevealDoesNothing() {
        val viewModel = seedCard()

        // No answer has been shown, so there is nothing to grade yet. The
        // footers never offer a card grade before the reveal; this is the
        // defence behind them.
        viewModel.grade(ReviewGrade.GOOD)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)

        val state = viewModel.state.value
        assertFalse("the ignored tap must not claim the card", state.grading)
        assertFalse("and must not reveal it", state.revealed)
        assertEquals("the card stays on screen", "card1", state.current?.questionId)
        assertEquals(0, state.flashcardsReviewed)
        assertEquals("nothing may be persisted", 0, runBlocking { reviews().size })
    }

    @Test
    fun aClassicModeCardGradeWritesTheSameRow() {
        // The classic footer shows the answer the same way; the write must be
        // the same row, not a second flashcard path.
        runBlocking { repository.setSimplifiedStudy(false) }
        val viewModel = seedCard()
        assertFalse("classic mode must be on", viewModel.state.value.simplified)

        viewModel.reveal()
        viewModel.grade(ReviewGrade.EASY)

        assertTrue("the grade must persist", awaitCondition {
            runBlocking { reviews().size } == 1
        })
        val row = runBlocking { reviews() }.single()
        assertEquals("the chosen grade is the stored rating", 4, row.rating)
        assertNull("a card has no correct answer to record", row.wasCorrect)
        assertNull("a card is never a declared guess", row.wasGuess)
        assertEquals("nothing was skipped", false, row.wasSkipped)
        assertNull("the app proposed no grade for a card", row.proposedRating)
        assertEquals("the learner chose the grade", "CHOSEN", row.gradeSource)
    }

    /**
     * The card's stuck path, mirroring [StudyGradedStuckTest]'s MCQ harness:
     * the review persisted but the next pull failed. The footer's Retry
     * (`loadNext()`) must fetch the next card without writing the grade again,
     * and the stored row must still be the self-graded card row.
     */
    @Test
    fun aStuckCardGradeRecoversOnRetryWithoutRegrading() {
        val viewModel = seedTwoCards()
        // Authored order serves card1 first; derive both ids so the assertions
        // do not depend on which one the pull picked.
        val first = viewModel.state.value.current?.questionId
        assertTrue("a card must be on screen, was $first", first == "card1" || first == "card2")
        val firstId = first ?: error("no card on screen")
        val second = if (firstId == "card1") "card2" else "card1"

        viewModel.reveal()
        // The injected failure fires inside grade()'s pull, after the write.
        viewModel.failPullForTests = true
        viewModel.grade(ReviewGrade.GOOD)

        assertTrue(
            "state=${viewModel.state.value} — the pull must fail after the write",
            awaitCondition { viewModel.state.value.gradedAndStuck }
        )
        assertFalse("the buttons must stand down", viewModel.state.value.grading)
        assertEquals(
            "only the plain localized message belongs on the card",
            app.getString(R.string.study_next_card_failed),
            viewModel.state.value.gradeError
        )
        // The row persisted before the failure is the card's self-graded one.
        val row = runBlocking { reviews(firstId) }.single()
        assertEquals("the chosen grade is the stored rating", 3, row.rating)
        assertNull("a card has no correct answer to record", row.wasCorrect)
        assertNull("a card is never a declared guess", row.wasGuess)
        assertEquals("nothing was skipped", false, row.wasSkipped)
        assertNull("the app proposed no grade for a card", row.proposedRating)
        assertEquals("the learner chose the grade", "CHOSEN", row.gradeSource)

        // What the footer's Retry calls: pull the next card, never re-grade.
        viewModel.loadNext()

        assertTrue("state=${viewModel.state.value}", awaitCondition {
            viewModel.state.value.current?.questionId == second
        })
        assertFalse("the retry must clear the stuck state", viewModel.state.value.gradedAndStuck)
        assertEquals(
            "the retry must not write a second review",
            1,
            runBlocking { reviews(firstId) }.size
        )
    }

    @Test
    fun aGuessCannotBeDeclaredForACard() {
        val viewModel = seedCard()
        viewModel.setGuess(true)
        assertFalse("a card has nothing to be right about", viewModel.state.value.isGuess)
    }

    @Test
    fun stopAfterGradingWritesOneSessionRow() {
        val viewModel = seedCard()
        val sessionsBefore = runBlocking { sessionRows() }

        viewModel.reveal()
        viewModel.grade(ReviewGrade.EASY)
        // The only card is graded, so the pull runs dry and the sitting closes.
        assertTrue("the sitting must finish", awaitCondition { viewModel.state.value.finished })

        assertEquals(1, runBlocking { reviews().size })
        assertTrue("a graded sitting must be recorded once", awaitCondition {
            runBlocking { sessionRows() } == sessionsBefore + 1
        })
        assertEquals(sessionsBefore + 1, runBlocking { sessionRows() })
    }

    @Test
    fun stopOnARevealedButUngradedCardWritesNothing() {
        val viewModel = seedCard()
        val sessionsBefore = runBlocking { sessionRows() }

        // Answer read, no grade chosen: the card stays exactly where it was.
        viewModel.reveal()
        viewModel.stop()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.finished })

        assertEquals("an ungraded card must not write a review", 0, runBlocking { reviews().size })
        assertEquals("and no sitting was spent", sessionsBefore, runBlocking { sessionRows() })
    }
}
