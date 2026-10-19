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

/**
 * Anki's reviewer reads one card ahead. In a big paper that is the difference
 * between Next being a frame and Next being a database read; this pins that
 * the card is resolved before it is asked for, so showing it needs no pump.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class TestSessionPrefetchTest {

    private lateinit var app: McqApplication

    // The run hung here (Gradle's "Could not stop all services" with this test
    // the last one started); dump the threads and fail fast if it recurs.
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

    /**
     * Clears the resume snapshot without deadlocking the main looper.
     *
     * This session's autosaves run in `viewModelScope` (Dispatchers.Main), and
     * DataStore runs an edit's transform in the *caller's* coroutine context,
     * so its write actor dispatches back onto this Robolectric main looper. A
     * plain `runBlocking` on the main thread blocks that looper, and both the
     * last queued autosave and the clear behind it then wait on a looper that
     * can never run. Clear off the main thread and keep the looper pumping
     * until the write actor drains.
     */
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

    /** Gives already-launched read-ahead coroutines time to land. */
    private fun settle() {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
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

    @Test
    fun theNextCardIsResolvedBeforeItIsShown() {
        seed(3)
        val vm = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(awaitCondition { vm.state.value.current?.id == "q0" })
        settle()

        // No idle after Next: a prefetched card is already in the state.
        vm.next()
        assertEquals("q1", vm.state.value.current?.id)
        assertEquals(
            "Q q1",
            (vm.state.value.current?.elements?.firstOrNull() as? ContentElement.TextElement)?.text
        )

        // And the read-ahead keeps following the reader.
        settle()
        vm.next()
        assertEquals("q2", vm.state.value.current?.id)
    }

    @Test
    fun aCoveredQuestionDoesNotResumeItsDwellWhenTheAppReturns() {
        seed(2)
        val vm = TestViewModel(app, "p1", listOf("c1"))
        assertTrue(awaitCondition {
            vm.state.value.currentQuestion != null && !vm.state.value.loading
        })
        // Same as the read-ahead test: let the load's own coroutines land
        // before the visibility dance, so nothing is still in flight at the
        // end of the sitting.
        settle()

        vm.setQuestionVisible(true)
        assertTrue("dwell accrues while the question is on screen", vm.state.value.dwellActive)

        // The palette opens over the question, then the app goes away and comes
        // back. questionVisible stays false, so its LaunchedEffect does not
        // re-fire; the foreground used to resume dwell behind the palette.
        vm.setQuestionVisible(false)
        vm.onAppBackgrounded()
        vm.onAppForegrounded()
        assertFalse("a covered question must not resume its dwell", vm.state.value.dwellActive)

        // Closing the palette starts it again.
        vm.setQuestionVisible(true)
        assertTrue(vm.state.value.dwellActive)

        // Leave the sitting idle and drained: this class's teardown is then the
        // last thing that writes, as it is for the read-ahead test above. A
        // session left clocking when the test ends keeps a coroutine on the
        // main looper past the class, and the next class's setup is a plain
        // runBlocking that blocks that looper (see the teardown's note).
        vm.setQuestionVisible(false)
        settle()
    }
}
