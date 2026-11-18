package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyPullGateForTests
import com.mcqapp.ui.study.StudyUiState
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regressions around the "graded but stuck" state: the grade was persisted but
 * the next-card pull failed. Two bugs lived there — stop() wrote the same review
 * a second time, and the simplified footer offered no way out of the dead end.
 *
 * The pull failure is injected through [StudyViewModel.failPullForTests], which
 * throws after the write has landed — the real shape of a locked/full disk
 * between the two steps.
 *
 * Robolectric + the real repository + the real database, so like every
 * DB-backed test it runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StudyGradedStuckTest {

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

    private fun question(id: String, categoryId: String = "c1") = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id?",
        options = listOf(
            QuestionOption("$id-a", "A"),
            QuestionOption("$id-b", "B")
        ),
        correctOptionIds = setOf("$id-a")
    )

    private fun seedOverdueRelearningAndFresh() = runBlocking {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 30 * day)
        repository.recordStudyReview("p1", "q1", ReviewGrade.AGAIN, now = now - 30 * day + 3_600_000L)
    }

    private suspend fun reviewLogCount(id: String): Int =
        repository.db().reviewLogDao().historyForQuestion(id).size

    /** Drives a real Check + Next whose pull fails after the grade was written. */
    private fun gradeWithFailedPull(viewModel: StudyViewModel) {
        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
        viewModel.failPullForTests = true
        viewModel.next()
        assertTrue(
            "state=${viewModel.state.value} — the pull must fail after the write",
            awaitCondition { viewModel.state.value.gradedAndStuck }
        )
        assertFalse(viewModel.state.value.grading)
        // The injected failure's own text must never be shown; only the plain
        // localized message belongs on the card.
        val message = viewModel.state.value.gradeError!!
        assertEquals(app.getString(R.string.study_next_card_failed), message)
        assertFalse(
            "raw exception detail must not reach the card: $message",
            message.contains("simulated next-card pull failure")
        )
    }

    /**
     * Puts the exact "write landed, pull failed" flag on the state, without
     * relying on the fault-injection hook — so this regression compiles and
     * fails against the unfixed code too.
     */
    @Suppress("UNCHECKED_CAST")
    private fun markGradedAndStuck(viewModel: StudyViewModel) {
        val field = StudyViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        val state = field.get(viewModel) as MutableStateFlow<StudyUiState>
        state.value = state.value.copy(gradedAndStuck = true)
    }

    @Test
    fun stopWithAStuckGradeDoesNotWriteItTwice() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(
            "state=${viewModel.state.value}",
            awaitCondition { viewModel.state.value.current != null }
        )
        val before = runBlocking { reviewLogCount("q1") }

        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
        markGradedAndStuck(viewModel)

        viewModel.stop()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.finished })

        // One row was already persisted before the pull failed. Stop only
        // closes the sitting; the old guard wrote the same grade a second time.
        assertEquals(before, runBlocking { reviewLogCount("q1") })
    }

    @Test
    fun stoppingAfterAPersistedGradeAndFailedPullWritesOnlyOneReview() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(
            "state=${viewModel.state.value}",
            awaitCondition { viewModel.state.value.current != null }
        )
        val before = runBlocking { reviewLogCount("q1") }

        gradeWithFailedPull(viewModel)
        assertEquals(
            "the failed pull must leave exactly the one review already written",
            before + 1,
            runBlocking { reviewLogCount("q1") }
        )

        viewModel.stop()
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.finished })

        // Back / "Done for now" only closes the sitting: the grade is already on
        // disk, so a second recordStudyReview here would duplicate the log row
        // and double the tally, FSRS state and exposure count.
        assertEquals(before + 1, runBlocking { reviewLogCount("q1") })
    }

    @Test
    fun stopDuringARetryPullKeepsTheSessionFinishedAndPublishesNoCard() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(
            "state=${viewModel.state.value}",
            awaitCondition { viewModel.state.value.current != null }
        )
        gradeWithFailedPull(viewModel)
        val before = runBlocking { reviewLogCount("q1") }

        // Hold the retry pull just before it would publish the next card.
        val gate = StudyPullGateForTests()
        viewModel.pullGateForTests = gate
        viewModel.loadNext()
        assertTrue("the retry pull must reach the gate", awaitCondition { gate.reached.isCompleted })

        // Back while the pull is in flight: stop() closes the sitting.
        viewModel.stop()
        gate.release.complete(Unit)
        assertTrue(
            "the gated pull must resolve",
            awaitCondition { gate.completed.isCompleted }
        )

        assertTrue("state=${viewModel.state.value}", viewModel.state.value.finished)
        assertEquals("no new card may be published after the stop", "q1", viewModel.state.value.current?.questionId)
        assertEquals("the retry must not re-grade", before, runBlocking { reviewLogCount("q1") })
        // stopped is latched: a second Back must stay on the summary.
        viewModel.stop()
        assertTrue(viewModel.state.value.finished)
    }

    @Test
    fun loadNextRecoversFromTheStuckStateWithoutRegrading() {
        seedOverdueRelearningAndFresh()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue(
            "state=${viewModel.state.value}",
            awaitCondition { viewModel.state.value.current != null }
        )
        val before = runBlocking { reviewLogCount("q1") }
        gradeWithFailedPull(viewModel)
        assertEquals("q1", viewModel.state.value.current?.questionId)

        // This is what the new simplified-footer Retry calls: only the next
        // card is missing, so it pulls it without writing another review.
        viewModel.loadNext()
        assertTrue("state=${viewModel.state.value}", awaitCondition {
            viewModel.state.value.current?.questionId == "q2"
        })
        assertFalse(viewModel.state.value.gradedAndStuck)
        assertEquals(before + 1, runBlocking { reviewLogCount("q1") })
    }
}
