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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * S4 behavioural counterpart: hold the grade write at [StudyGradeGateForTests]
 * and tap "change grade" before it resumes. The proposal and the result card
 * must still describe the grade that is actually persisted. Like every
 * DB-backed study test this needs CI's x86_64 Robolectric runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class Round2StudyChangeGradeDuringGradeTest {

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
    }

    @Test
    fun changeGradeDuringAnInFlightWriteIsIgnored() {
        seed()
        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })

        viewModel.toggleOption("q1-a")
        viewModel.check()
        val proposed = viewModel.state.value.pendingGrade
        assertNotNull("Check must propose a grade", proposed)
        val resultGrade = viewModel.state.value.lastResult?.grade
        assertEquals(proposed, resultGrade)

        val gate = StudyGradeGateForTests()
        viewModel.gradeGateForTests = gate
        viewModel.next()
        assertTrue("the write must reach the gate", awaitCondition { gate.reached.isCompleted })
        assertTrue("the wait must leave a grade in flight", viewModel.state.value.grading)

        // A tap in the frame before recomposition used to rewrite pendingGrade
        // and lastResult after next() captured `proposed`.
        viewModel.changeGrade(ReviewGrade.AGAIN)
        assertEquals(
            "the in-flight grade owns the proposal",
            proposed,
            viewModel.state.value.pendingGrade
        )
        assertEquals(
            "the result card must still describe the persisted grade",
            proposed,
            viewModel.state.value.lastResult?.grade
        )

        gate.release.complete(Unit)
        assertTrue("the grade must finish", awaitCondition { gate.completed.isCompleted })
    }
}
