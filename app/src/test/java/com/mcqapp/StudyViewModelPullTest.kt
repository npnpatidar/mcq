package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.domain.StudyReason
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The pull-based study loop end to end through the ViewModel: the session
 * grades, persists, and asks the scheduler what to pull next instead of
 * walking a prebuilt queue. The ViewModel was an index into a fixed list
 * once; these tests pin the replacement semantics — a failed card returns
 * within its sitting, the backlog is served before new material, stopping
 * and restarting never lose or invent progress.
 *
 * Robolectric + the real repository + the real database, so like every
 * DB-backed test it runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class StudyViewModelPullTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @After
    fun cleanup() = runBlocking {
        // The scheduler settings live in a process-shared DataStore and the
        // paper in the process-shared database: both are reset so neither a
        // mode nor a schedule leaks into another test.
        repository.resetSchedulerConfig()
        repository.deletePaper("p1")
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Idles the main looper until [condition] holds, or gives up. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun question(id: String) = com.mcqapp.domain.Question(
        id = id,
        categoryId = "c1",
        text = "Q $id?",
        options = listOf(
            com.mcqapp.domain.QuestionOption("$id-a", "A"),
            com.mcqapp.domain.QuestionOption("$id-b", "B")
        ),
        correctOptionIds = setOf("$id-a")
    )

    /**
     * q1: graduated a month ago, lapsed an hour later — its relearning step is
     * long overdue. q2: never studied. The pull must put q1 on screen first.
     */
    private fun seedOverdueRelearningAndFresh() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 30 * day)
        repository.recordStudyReview("p1", "q1", ReviewGrade.AGAIN, now = now - 30 * day + 3_600_000L)
    }

    @Test
    fun gradingPersistsTheReviewAndPullsTheNextRankedCard() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { viewModel.state.value.current != null })

        // The overdue relearning step wins the pull before any new card.
        val first = viewModel.state.value.current!!
        assertEquals("q1", first.questionId)
        assertEquals(StudyReason.LEARNING, first.reason)

        // Simplified grading from the answer: correct, answered instantly.
        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertTrue(viewModel.state.value.revealed)
        assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
        viewModel.next()

        // The next card arrives by pull, not by queue position: the backlog is
        // empty after that Good, so the fresh card is served.
        assertTrue(awaitCondition { viewModel.state.value.reviewed == 1 })
        assertTrue(awaitCondition {
            viewModel.state.value.current?.questionId == "q2"
        })
        assertEquals(StudyReason.NEW, viewModel.state.value.current!!.reason)
        // The previews follow the card on screen, not the graded one.
        assertEquals(4, viewModel.state.value.previews.size)

        // And the grade itself persisted: graduated, lapse kept, logged.
        runBlocking {
            val state = repository.cardState("p1", "q1")!!
            assertEquals(3, state.reviewCount)
            assertEquals(1, state.lapses)
            assertEquals(com.mcqapp.domain.CardStateKind.REVIEW, state.state)
            assertEquals(3, repository.db().reviewLogDao().historyForQuestion("q1").size)
        }
    }

    @Test
    fun stopEndsTheSessionWithItsTallyAndLeavesSchedulesIntact() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { viewModel.state.value.current != null })

        viewModel.stop()
        assertTrue(awaitCondition { viewModel.state.value.finished })
        // A voluntary stop, not an empty session: no "nothing due" claim.
        assertEquals("", viewModel.state.value.emptyReason)
        assertEquals(0, viewModel.state.value.reviewed)
        runBlocking {
            assertEquals(2, repository.cardState("p1", "q1")!!.reviewCount)
        }
    }

    @Test
    fun restartResetsTheTallyAndRepullsFromTheScheduler() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { viewModel.state.value.current != null })
        viewModel.toggleOption("q1-a")
        viewModel.check()
        viewModel.next()
        assertTrue(awaitCondition { viewModel.state.value.reviewed == 1 })

        viewModel.restart()
        assertTrue(awaitCondition {
            viewModel.state.value.reviewed == 0 && viewModel.state.value.current != null
        })
        // q1 was graduated out of this sitting by its Good, so the fresh card
        // is what the scheduler serves — the same pull semantics as before.
        assertEquals("q2", viewModel.state.value.current!!.questionId)
    }

    @Test
    fun anEmptySessionOffersToTurnNewCardsOnAndHonoursTheFlip() {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            repository.setSchedulerConfig(
                repository.schedulerConfigNow().copy(introduceNewCards = false)
            )
        }
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { viewModel.state.value.finished })
        assertEquals("Nothing due right now.", viewModel.state.value.emptyReason)
        // New cards exist behind the off toggle, so the empty state can offer
        // exactly that flip — counting under the off flag would always say no.
        assertTrue(viewModel.state.value.freshAvailable)

        viewModel.enableNewCards()
        assertTrue(awaitCondition { viewModel.state.value.current != null })
        assertEquals("q1", viewModel.state.value.current!!.questionId)
        assertTrue(viewModel.state.value.introduceNewCards)
        runBlocking {
            assertTrue(repository.schedulerConfigNow().introduceNewCards)
        }
    }

    @Test
    fun aGuessedCardIsProposedAgainAndChangeGradeEditsTheProposalBeforeItPersists() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { viewModel.state.value.current != null })

        // A declared guess that turns out right is Again: FSRS records the
        // absence of recall, not the lucky match. The proposal is not yet a
        // review — only Next persists.
        viewModel.setGuess(true)
        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertEquals(ReviewGrade.AGAIN, viewModel.state.value.pendingGrade)
        assertEquals(true, viewModel.state.value.lastResult?.wasGuess)

        viewModel.next()
        assertTrue(awaitCondition {
            viewModel.state.value.current?.questionId == "q2"
        })
        runBlocking {
            assertEquals(ReviewGrade.AGAIN.ordinal + 1, repository.db().reviewLogDao()
                .historyForQuestion("q1").last().rating)
        }

        // The same path unchanged for a plain correct answer: GOOD is what
        // Check proposes. Change-grade edits the proposal, and Next persists
        // what is on screen, not the original inference.
        viewModel.toggleOption("q2-a")
        viewModel.check()
        assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
        viewModel.changeGrade(ReviewGrade.EASY)
        assertEquals(ReviewGrade.EASY, viewModel.state.value.pendingGrade)
        assertEquals(false, viewModel.state.value.lastResult?.wasGuess)

        viewModel.next()
        assertTrue(awaitCondition { viewModel.state.value.reviewed == 2 })
        runBlocking {
            assertEquals(4, repository.db().reviewLogDao()
                .historyForQuestion("q2").last().rating)
        }
    }

    @Test
    fun revealingAQuestionFreezesItsClockAndTheNextCardRestartsIt() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { viewModel.state.value.current != null })
        assertTrue(viewModel.state.value.clockRunning)

        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertTrue(viewModel.state.value.revealed)
        assertFalse(viewModel.state.value.clockRunning)

        viewModel.next()
        assertTrue(awaitCondition { viewModel.state.value.current?.questionId == "q2" })
        assertTrue(viewModel.state.value.clockRunning)
        assertEquals(0L, viewModel.state.value.questionElapsedSeconds)
    }

    @Test
    fun tappingTheQuestionClockPausesAndResumesIt() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { viewModel.state.value.current != null })

        viewModel.toggleQuestionClock()
        assertFalse(viewModel.state.value.clockRunning)

        viewModel.toggleQuestionClock()
        assertTrue(viewModel.state.value.clockRunning)
    }
}
