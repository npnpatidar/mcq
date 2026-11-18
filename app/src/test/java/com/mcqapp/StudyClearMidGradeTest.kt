package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyGradeGateForTests
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A bare ViewModelStore.clear() is not the normal Back/Done path: stop() never
 * runs, so the "sitting ended" latch is false. A grade still in flight on the
 * durable session scope persists its review *after* onCleared ran — and
 * onCleared saw reviewed == 0, so it wrote no study_sessions row. The grade
 * continuation must end the sitting once its review is tallied, and the CAS
 * must keep that to exactly one row.
 *
 * The grade write is held at [StudyViewModel.gradeGateForTests] until after a
 * real ViewModelStore.clear() (which cancels viewModelScope and calls
 * onCleared), so the interleaving is deterministic. Like every DB-backed test
 * this needs CI's x86_64 Robolectric runtime, not the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StudyClearMidGradeTest {

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

    private suspend fun reviewCount(id: String): Int =
        repository.db().reviewLogDao().historyForQuestion(id).size

    private suspend fun sessionRowCount(): Int =
        repository.db().studySessionDao().getAll().size

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
    }

    @Test
    fun clearDuringAnInFlightGradeWritesExactlyOneSessionRow() {
        seed()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })
        val before = runBlocking { reviewCount("q1") }
        val sessionsBefore = runBlocking { sessionRowCount() }

        // Check proposes a grade; the write is held before the repository call.
        viewModel.toggleOption("q1-a")
        viewModel.check()
        assertEquals(ReviewGrade.GOOD, viewModel.state.value.pendingGrade)
        val gate = StudyGradeGateForTests()
        viewModel.gradeGateForTests = gate
        viewModel.next()
        assertTrue("the grade must reach the gate", awaitCondition { gate.reached.isCompleted })
        assertEquals(before, runBlocking { reviewCount("q1") })

        // The composable leaving the graph clears the ViewModel. No stop(), no
        // summary: onCleared sees reviewed == 0 and writes nothing itself.
        val store = ViewModelStore()
        store.put("study", viewModel)
        store.clear()
        gate.release.complete(Unit)

        assertTrue("the persisted grade must land after the clear", awaitCondition {
            runBlocking { reviewCount("q1") } == before + 1
        })
        // Old code stopped here with one review and zero session rows.
        assertTrue("a reviewed sitting must add exactly one session row", awaitCondition {
            runBlocking { sessionRowCount() } == sessionsBefore + 1
        })

        // Give any stray second write a chance to surface before asserting.
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)
        assertEquals(before + 1, runBlocking { reviewCount("q1") })
        assertEquals(
            "the clear must never write two session rows",
            sessionsBefore + 1,
            runBlocking { sessionRowCount() }
        )
    }
}
