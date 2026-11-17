package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.test.TestViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A timed test's countdown must not keep running behind a blocking overlay.
 *
 * The screen funnels every cover — the ungraded note, the question palette,
 * the submit confirmation, the answer-review sheet, the resume dialog — into
 * one [TestViewModel.setQuestionVisible] call. The dwell clock already froze
 * behind those covers, but the exam countdown used to tick on regardless and
 * auto-submit at zero while the learner could not even see the question.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class TestSessionTimerOverlayTest {

    private lateinit var app: McqApplication

    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 90)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    }

    @After
    fun tearDown() {
        try {
            clearProgressWithoutBlockingMain()
            AppDatabase::class.java
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .set(null, null)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Same non-blocking clear as [TestSessionResumeTest]. */
    private fun clearProgressWithoutBlockingMain() {
        val done = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repository.clearTestProgress()
            } finally {
                done.complete(Unit)
            }
        }
        repeat(600) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done.isCompleted) return
            Thread.sleep(5)
        }
        assertTrue("clearTestProgress() never completed", done.isCompleted)
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun seed(count: Int) = runBlocking {
        app.repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repeat(count) { i ->
            app.repository.saveQuestion(
                Question(
                    id = "q$i",
                    categoryId = "c1",
                    elements = listOf(ContentElement.TextElement("Q q$i")),
                    options = listOf(QuestionOption("a", "A"), QuestionOption("b", "B")),
                    correctOptionIds = setOf("a")
                )
            )
        }
    }

    /**
     * Hold an overlay open for longer than the whole sitting: the countdown is
     * frozen and cannot auto-submit until the overlay is dismissed, then the
     * same clock resumes from where it froze and submits at zero.
     *
     * On the old code `setQuestionVisible(false)` only paused dwell; the timer
     * kept ticking, reached zero behind the cover and submitted, so the first
     * `assertFalse` below failed.
     */
    @Test
    fun aCountdownHeldBehindAnOverlayNeverAutoSubmits() {
        seed(3)
        // Two minutes so the load's own looper pumping cannot reach zero before
        // the overlay goes up; virtual time makes the long waits free.
        val vm = TestViewModel(app, "p1", listOf("c1"), durationMinutes = 2)
        assertTrue(awaitCondition { !vm.state.value.loading && vm.state.value.currentQuestion != null })

        // The screen reports the question visible while nothing covers it, then
        // a blocking dialog/palette/review sheet goes up over it.
        vm.setQuestionVisible(true)
        vm.setQuestionVisible(false)
        val frozen = vm.state.value.remainingSeconds
        assertTrue("a running countdown is required for this test", frozen > 0)

        // All of the sitting (plus slack) elapses while the cover is up.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(frozen.toLong() + 5))
        assertFalse(
            "a timed test auto-submitted behind an overlay",
            vm.state.value.submitted
        )
        assertEquals(
            "the countdown must not tick behind an overlay",
            frozen, vm.state.value.remainingSeconds
        )

        // Dismissing the overlay resumes the very same clock; it must still be
        // able to finish the attempt at zero.
        vm.setQuestionVisible(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(frozen.toLong() + 5))
        assertTrue("the countdown must auto-submit once the overlay is gone", vm.state.value.submitted)
        assertTrue(
            "the resumed countdown never finished its save",
            awaitCondition { vm.state.value.attemptId != null && !vm.state.value.saving }
        )
    }
}
