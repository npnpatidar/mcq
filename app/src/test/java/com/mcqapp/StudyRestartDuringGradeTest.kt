package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyGradeGateForTests
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * S1: a grade write still in flight when the sitting is stopped and then
 * restarted must be attributed to the sitting that earned it. The old code
 * cleared the stop latch at the top of [StudyViewModel.restart] and only joined
 * the session-end write, so the late grade continuation saw a live sitting:
 * it tallied the review into the fresh sitting, pulled a second card, never
 * wrote the original sitting's study_sessions row, and could drop a late grade
 * error onto the new card.
 *
 * The write is held at [StudyViewModel.gradeGateForTests] so the interleaving
 * is deterministic. Like every DB-backed test this needs CI's x86_64 Robolectric
 * runtime, not the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StudyRestartDuringGradeTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @get:Rule
    val settingsReset = SettingsResetRule()

    @After
    fun cleanup() = runBlocking {
        repository.resetSchedulerConfig()
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

    private fun seed() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(
            Question(
                id = "q1",
                categoryId = "c1",
                text = "Q q1?",
                options = listOf(
                    QuestionOption("q1-a", "A"),
                    QuestionOption("q1-b", "B")
                ),
                correctOptionIds = setOf("q1-a")
            )
        )
        // One overdue relearning step so the pull always has a card, without
        // depending on the introduce-new-cards toggle other tests may leave set.
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 30 * day)
        repository.recordStudyReview("p1", "q1", ReviewGrade.AGAIN, now = now - 30 * day + 3_600_000L)
    }

    private suspend fun reviewCount(id: String): Int =
        repository.db().reviewLogDao().historyForQuestion(id).size

    private suspend fun sessionRowCount(): Int =
        repository.db().studySessionDao().getAll().size

    /**
     * Hold the grade, stop (so the original sitting ends with reviewed == 0 and
     * defers its row to the grade), then restart and release. The review must
     * still land once, the original sitting must get its one session row, and
     * the fresh sitting must start with a clean tally and no inherited error.
     * On the old code the row count stayed unchanged and reviewed became 1.
     */
    @Test
    fun restartAfterStopDuringGradeAttributesReviewToOriginalSitting() {
        seed()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        val beforeReviews = runBlocking { reviewCount("q1") }
        val beforeSessions = runBlocking { sessionRowCount() }

        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
        val gate = StudyGradeGateForTests()
        viewModel.gradeGateForTests = gate
        viewModel.next()
        assertTrue("the grade must reach the gate", awaitCondition { gate.reached.isCompleted })
        assertEquals(beforeReviews, runBlocking { reviewCount("q1") })

        // Back / "Done" while the write is in flight, then "Study again".
        viewModel.stop()
        assertTrue("stop must finish the old sitting", awaitCondition { viewModel.state.value.finished })
        viewModel.restart()
        gate.release.complete(Unit)

        assertTrue("the in-flight grade must still persist", awaitCondition {
            runBlocking { reviewCount("q1") } == beforeReviews + 1
        })
        assertTrue("the original sitting must get exactly one session row", awaitCondition {
            runBlocking { sessionRowCount() } == beforeSessions + 1
        })
        assertTrue("state=${viewModel.state.value}", awaitCondition {
            !viewModel.state.value.finished &&
                !viewModel.state.value.loading &&
                viewModel.state.value.current != null
        })

        // The fresh sitting excludes the late grade: no tally or error leaked
        // across from the sitting that earned it.
        assertEquals(0, viewModel.state.value.reviewed)
        assertNull(viewModel.state.value.gradeError)

        // Give any stray second write/pull time to surface before re-checking.
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)
        assertEquals(beforeReviews + 1, runBlocking { reviewCount("q1") })
        assertEquals(beforeSessions + 1, runBlocking { sessionRowCount() })
        assertEquals(0, viewModel.state.value.reviewed)
    }

    /**
     * Control case: a restart with nothing in flight still opens a fresh sitting
     * and a normal grade + stop still writes exactly one session row for it.
     * This passes on both old and new code and guards against a fix that simply
     * stopped restarting sittings.
     */
    @Test
    fun restartWithNoGradeInFlightStillCreatesAndEndsSittings() {
        seed()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        val beforeSessions = runBlocking { sessionRowCount() }
        val beforeReviews = runBlocking { reviewCount("q1") }

        // Stop before any review: no row yet, but a finished sitting.
        viewModel.stop()
        assertTrue(awaitCondition { viewModel.state.value.finished })
        assertEquals(beforeSessions, runBlocking { sessionRowCount() })

        // No grade in flight: a live, fresh sitting that re-pulls the card.
        viewModel.restart()
        assertTrue("state=${viewModel.state.value}", awaitCondition {
            !viewModel.state.value.finished &&
                viewModel.state.value.reviewed == 0 &&
                viewModel.state.value.current?.questionId == "q1"
        })

        viewModel.toggleOption("q1-a")
        viewModel.check()
        viewModel.next()
        assertTrue("state=${viewModel.state.value}", awaitCondition {
            runBlocking { reviewCount("q1") } == beforeReviews + 1 &&
                viewModel.state.value.reviewed == 1
        })

        viewModel.stop()
        assertTrue(awaitCondition { viewModel.state.value.finished })
        assertTrue("the fresh sitting must write its own single row", awaitCondition {
            runBlocking { sessionRowCount() } == beforeSessions + 1
        })
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)
        assertEquals(beforeSessions + 1, runBlocking { sessionRowCount() })
        assertEquals(1, viewModel.state.value.reviewed)
    }
}
