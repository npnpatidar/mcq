package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.EmptyReason
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Clock and notice behaviour across the two exits the structure tests cannot
 * see: taking the empty-state offer to enable new cards, pausing for a break,
 * and the one-shot leech crossing notice. Robolectric with the real repository
 * and database, like every DB-backed study test, so CI (x86_64) is authoritative.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class StudyPolishSessionTest {

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

    private fun field(name: String, vm: StudyViewModel): Any? =
        StudyViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm)

    private fun bool(name: String, vm: StudyViewModel): Boolean = field(name, vm) as Boolean
    private fun long(name: String, vm: StudyViewModel): Long = field(name, vm) as Long
    private fun setLong(name: String, vm: StudyViewModel, value: Long) {
        StudyViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
            .set(vm, value)
    }

    /**
     * finishEmpty() froze both clocks and closed the sitting. The offer to turn
     * new cards on continues that sitting, so both clocks and the session row's
     * wall-clock start must be re-based before the first card is pulled —
     * otherwise the card's dwell stays at zero and the session row keeps the
     * empty sitting's timestamp.
     */
    @Test
    fun enablingNewCardsFromTheEmptyStateRestartsBothClocksAndTheSitting() {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            repository.setSchedulerConfig(
                repository.schedulerConfigNow().copy(introduceNewCards = false)
            )
        }
        val vm = StudyViewModel(app, "p1")
        assertTrue("state=${vm.state.value}", awaitCondition { vm.state.value.finished })
        assertEquals(EmptyReason.NothingDue, vm.state.value.emptyReason)
        assertFalse("the empty state banks the sitting clock", bool("sessionRunning", vm))
        // Make a stale start unmistakable: a reset must move it forward.
        setLong("sessionStartedAtWall", vm, 1_000L)

        vm.enableNewCards()
        assertTrue(
            "state=${vm.state.value}",
            awaitCondition { vm.state.value.current?.questionId == "q1" && !vm.state.value.loading }
        )
        assertTrue("the first card must be clocked", vm.state.value.clockRunning)
        assertEquals(0L, vm.state.value.questionElapsedMillis)
        assertTrue("the sitting clock must run again", bool("sessionRunning", vm))
        assertEquals(0L, long("sessionBankedSeconds", vm))
        // The ticker may already have published its first live second; the
        // point is that time is flowing again, not that it is still exactly 0.
        assertTrue("session seconds ${vm.sessionSeconds.value}", vm.sessionSeconds.value < 5L)
        assertEquals(0L, vm.state.value.sessionTotalSeconds)
        assertFalse(vm.state.value.finished)
        assertTrue(
            "the session row must get a fresh wall-clock start: ${long("sessionStartedAtWall", vm)}",
            long("sessionStartedAtWall", vm) > 1_000L
        )
    }

    /** A break banks both clocks and parks answering, but keeps the sitting open. */
    @Test
    fun takeBreakBanksBothClocksAndResumeKeepsTheSittingOpen() {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            repository.saveQuestion(question("q2"))
        }
        val vm = StudyViewModel(app, "p1")
        assertTrue("state=${vm.state.value}", awaitCondition { vm.state.value.current != null })
        assertTrue(vm.state.value.clockRunning)

        vm.takeBreak()
        assertTrue(vm.state.value.onBreak)
        assertFalse(vm.state.value.clockRunning)
        assertFalse("the sitting clock must be banked too", bool("sessionRunning", vm))
        assertFalse("the sitting must NOT end on a break", vm.state.value.finished)
        assertNotNull(vm.state.value.current)
        val frozen = vm.sessionSeconds.value

        // Answering is parked: a tap in the frame before recomposition must not
        // propose a grade while the clocks are stopped.
        vm.toggleOption("q1-a")
        vm.check()
        assertNull(vm.state.value.pendingGrade)
        assertEquals(frozen, vm.sessionSeconds.value)

        vm.resumeFromBreak()
        assertFalse(vm.state.value.onBreak)
        assertTrue(bool("sessionRunning", vm))
        assertTrue(vm.state.value.clockRunning)
        assertFalse(vm.state.value.finished)

        // The sitting still works after the break.
        vm.toggleOption("q1-a")
        vm.check()
        assertNotNull(vm.state.value.pendingGrade)
        vm.next()
        assertTrue("state=${vm.state.value}", awaitCondition { vm.state.value.reviewed == 1 })
    }

    /**
     * Scope switching navigates to a fresh study route, which retains the old
     * ViewModel in the back stack. The screen now calls stop() on that old VM
     * before navigating (like Back and "Done for now"). Its sitting clock is
     * banked at the switch and no ticker keeps publishing a running clock, so a
     * later pop cannot write an inflated study_sessions row.
     */
    @Test
    fun stoppingTheOldSittingBeforeAScopeSwitchBanksItsClockAndStopsTheTicker() {
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            repository.saveQuestion(question("q2"))
        }
        val vm = StudyViewModel(app, "p1")
        assertTrue("state=${vm.state.value}", awaitCondition { vm.state.value.current != null })
        assertTrue("a live sitting runs its clock", bool("sessionRunning", vm))
        assertTrue(vm.state.value.clockRunning)

        // One review in the bank, mirroring a switch mid-sitting — the case that
        // used to leave the old ticker running and inflate the row on a pop.
        vm.toggleOption("q1-a")
        vm.check()
        vm.next()
        assertTrue("state=${vm.state.value}", awaitCondition { vm.state.value.reviewed == 1 })

        // What each scope handler now does before navController.navigate(...).
        vm.stop()
        assertTrue("state=${vm.state.value}", awaitCondition { vm.state.value.finished })

        assertFalse("the old sitting's clock must be banked", bool("sessionRunning", vm))
        assertFalse(vm.state.value.clockRunning)
        val frozen = vm.sessionSeconds.value
        // Even if the ticker coroutine is still alive, a stopped sitting
        // publishes a frozen clock: no live seconds accrue on later ticks.
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertEquals("the clock must not grow after a scope switch", frozen, vm.sessionSeconds.value)

        // Exactly one session row, written at the switch rather than inflated
        // later; a repeated stop (or a later pop) must not add a second one.
        assertTrue("the sitting row must be written", awaitCondition {
            runBlocking { repository.db().studySessionDao().getAll().size } == 1
        })
        vm.stop()
        runBlocking { assertEquals(1, repository.db().studySessionDao().getAll().size) }
    }

    /** Crossing the leech threshold is announced once, on the card that follows. */
    @Test
    fun crossingTheLeechThresholdShowsTheNoticeOnce() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        runBlocking {
            repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
            repository.saveQuestion(question("q1"))
            repository.saveQuestion(question("q2"))
            repository.setSchedulerConfig(
                repository.schedulerConfigNow().copy(leechThreshold = 2)
            )
            // A graduated card with one lapse: one more failure crosses the
            // threshold of two. The stored row is a real review; only its due
            // date and shape are nudged so the pull serves it now.
            repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 30 * day)
            repository.recordStudyReview("p1", "q1", ReviewGrade.AGAIN, now = now - 29 * day)
            repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD, now = now - 28 * day)
            repository.db().openHelper.writableDatabase.execSQL(
                "UPDATE card_state SET cardState = 'REVIEW', lapses = 1, leech = 0, " +
                    "stability = 10.0, dueAt = ?, lastReviewedAt = ? WHERE questionId = 'q1'",
                arrayOf<Any>(now - day, now - 28 * day)
            )
        }
        val vm = StudyViewModel(app, "p1")
        assertTrue("state=${vm.state.value}", awaitCondition { vm.state.value.current != null })
        assertEquals("q1", vm.state.value.current!!.questionId)
        assertFalse(vm.state.value.current!!.state.leech)
        assertFalse(vm.state.value.justFlaggedLeech)

        // A wrong answer from a REVIEW card adds the second lapse and flags it.
        vm.toggleOption("q1-b")
        vm.check()
        assertEquals(ReviewGrade.AGAIN, vm.state.value.pendingGrade)
        vm.next()
        assertTrue(
            "state=${vm.state.value}",
            awaitCondition { vm.state.value.current?.questionId == "q2" }
        )
        assertTrue("the crossing must be shown", vm.state.value.justFlaggedLeech)
        assertTrue(runBlocking { repository.cardState("p1", "q1")!!.leech })

        // Grading the next card clears the one-shot notice: a flagged card that
        // stays flagged must not announce itself on every answer.
        vm.toggleOption("q2-a")
        vm.check()
        vm.next()
        assertTrue("state=${vm.state.value}", awaitCondition { vm.state.value.finished })
        assertFalse(vm.state.value.justFlaggedLeech)
    }
}
