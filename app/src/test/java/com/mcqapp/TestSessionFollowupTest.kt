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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Follow-ups on the test-session review: a failed attempt write must not be
 * charged to the learner's recorded duration, and a deliberate dwell pause must
 * reach disk just like backgrounding does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class TestSessionFollowupTest {

    private lateinit var app: McqApplication

    @get:Rule
    val watchdog = HangWatchdogRule(seconds = 90)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    }

    @After
    fun tearDown() {
        TestViewModel.beforeSaveForTest = null
        TestViewModel.failSaveForTest = null
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
     * The first save is held open for 60 s of virtual time and then fails. The
     * retry must record only the learner's own 5 s before it plus the 3 s after
     * it — not the 68 s the monotonic clock saw. On the old code the failed
     * write's 60 s were charged, so the retry stored 68.
     */
    @Test
    fun aFailedSaveDoesNotChargeTheFailedWindowOnRetry() {
        seed(2)
        // Untimed: the duration comes from the monotonic session anchor that
        // the failure path has to rebase.
        val vm = TestViewModel(app, "p1", listOf("c1"), durationMinutes = 0)
        assertTrue(awaitCondition { vm.state.value.current?.id == "q0" })
        vm.toggleOption("a")

        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        shadowOf(Looper.getMainLooper()).idle()

        val gate = CompletableDeferred<Unit>()
        var saves = 0
        TestViewModel.beforeSaveForTest = { gate.await() }
        TestViewModel.failSaveForTest = { ++saves == 1 }
        try {
            vm.submit()
            assertTrue("the first save never started", vm.state.value.saving)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
            gate.complete(Unit)
            assertTrue(
                "the failed save never unlocked the screen",
                awaitCondition { vm.state.value.saveError != null && !vm.state.value.saving }
            )
            assertFalse(vm.state.value.submitted)
        } finally {
            TestViewModel.beforeSaveForTest = null
            TestViewModel.failSaveForTest = null
            gate.complete(Unit)
        }

        ShadowSystemClock.advanceBy(Duration.ofSeconds(3))
        vm.submit()
        assertTrue(
            "the retry never landed",
            awaitCondition { vm.state.value.attemptId != null && !vm.state.value.saving }
        )

        val attempts = runBlocking { app.repository.db().attemptDao().getByPaper("p1") }
        assertEquals(1, attempts.size)
        assertEquals(
            "the failed write's 60 s were charged as exam time",
            8L, attempts.single().durationSeconds
        )
    }

    /**
     * Tap-to-pause banks dwell but used to leave it in memory only. A kill in
     * that state restored the older (zero) dwell from disk. The pause must do
     * the same persistProgress() the background path does.
     */
    @Test
    fun aDeliberatePausePersistsTheBankedDwellBeforeReturning() {
        seed(3)
        val vm = TestViewModel(app, "p1", listOf("c1"), durationMinutes = 0)
        assertTrue(awaitCondition { vm.state.value.current?.id == "q0" })

        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        shadowOf(Looper.getMainLooper()).idle()

        vm.toggleDwellPause()
        assertTrue(vm.state.value.dwellPausedByUser)
        assertFalse("a deliberate pause must stop dwell", vm.state.value.dwellActive)

        assertTrue(
            "the paused dwell was left only in memory",
            awaitCondition {
                runBlocking { app.repository.db().testProgressDao().getForPaper("p1") }
                    .firstOrNull { it.questionId == "q0" }?.dwellSeconds == 5L
            }
        )
    }
}
