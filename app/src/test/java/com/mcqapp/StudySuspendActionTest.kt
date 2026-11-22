package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.DayBoundary
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Study-side card actions. A suspend or bury claims the card like a grade
 * and pulls the next one, but must never call [McqRepository.recordStudyReview]:
 * no grade, no review_log row, no FSRS interval. Unsuspend is reachable from
 * the same screen, so a hidden card is never stranded.
 *
 * Robolectric + the process-wide DataStore/database; CI (x86_64) is authoritative.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StudySuspendActionTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    private val repository get() = app.repository

    @get:Rule
    val settingsReset = SettingsResetRule()

    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 120)

    @Before
    fun clearPaper(): Unit = runBlocking {
        repository.deletePaper("p1")
    }

    @After
    fun cleanup(): Unit = runBlocking {
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

    private fun question(id: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Question $id?",
        options = listOf(QuestionOption("$id-a", "A"), QuestionOption("$id-b", "B")),
        correctOptionIds = setOf("$id-a")
    )

    private suspend fun seed() {
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1"))
        repository.saveQuestion(question("q2"))
    }

    private fun db(): AppDatabase = AppDatabase.get(app.applicationContext)

    @Test
    fun suspendingTheCurrentCardPullsTheNextWithoutWritingAReview() {
        runBlocking { seed() }
        val vm = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { vm.state.value.current?.questionId == "q1" })

        vm.suspendCurrent()

        assertTrue("the next card is pulled", awaitCondition {
            vm.state.value.current?.questionId == "q2" &&
                vm.state.value.cardActionInProgress.not()
        })
        val state = runBlocking { repository.cardState("p1", "q1") }
        assertNotNull("the card has a schedule row to hold the flag", state)
        assertTrue("the card is suspended", state!!.suspended)
        assertEquals(0, state.reps)
        assertEquals(0L, state.dueAt)
        assertEquals(0L, state.lastReviewedAt)
        assertTrue(
            "no grade was written",
            runBlocking { db().reviewLogDao().getAll() }.none { it.paperId == "p1" }
        )
    }

    @Test
    fun buryingTheCurrentCardUsesTheNextStudyDayBoundary() {
        runBlocking { seed() }
        val config = runBlocking { repository.schedulerConfigNow() }
        val before = System.currentTimeMillis()
        val vm = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { vm.state.value.current?.questionId == "q1" })

        vm.buryCurrent()

        assertTrue(awaitCondition { vm.state.value.current?.questionId == "q2" })
        val buriedUntil = runBlocking { repository.cardState("p1", "q1")!!.buriedUntil }
        assertNotNull(buriedUntil)
        val expected = DayBoundary.dueAfter(before, 1, config.dayStartHour, DayBoundary.defaultZone())
        // The write may land a tick after `before`; either boundary is the very
        // next one, so accept the deadline computed at either instant.
        val expectedAfter = DayBoundary.dueAfter(
            System.currentTimeMillis(), 1, config.dayStartHour, DayBoundary.defaultZone()
        )
        assertTrue(
            "buriedUntil=$buriedUntil expected $expected..$expectedAfter",
            buriedUntil == expected || buriedUntil == expectedAfter
        )
        assertTrue(
            "no grade was written",
            runBlocking { db().reviewLogDao().getAll() }.none { it.paperId == "p1" }
        )
    }

    @Test
    fun unsuspendFromTheStudyDialogRestoresTheCardToThePool() {
        runBlocking { seed() }
        val first = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { first.state.value.current?.questionId == "q1" })
        first.suspendCurrent()
        assertTrue(awaitCondition { first.state.value.current?.questionId == "q2" })

        first.openSuspendedDialog()
        assertTrue(awaitCondition {
            first.state.value.showSuspendedDialog &&
                first.state.value.suspendedCards.map { it.questionId } == listOf("q1")
        })
        first.unsuspend("q1")
        assertTrue(awaitCondition { first.state.value.suspendedCards.isEmpty() })
        first.closeSuspendedDialog()
        assertFalse(first.state.value.showSuspendedDialog)

        // A fresh session can pull the card again.
        val second = StudyViewModel(app, "p1")
        assertTrue(awaitCondition { second.state.value.current?.questionId == "q1" })
    }
}
