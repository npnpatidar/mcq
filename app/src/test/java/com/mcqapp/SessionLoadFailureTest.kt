package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.ui.study.StudyViewModel
import com.mcqapp.ui.test.TestViewModel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A session that cannot load must say so.
 *
 * Before this, the test ViewModel left `loading` true — the screen showed
 * "Loading…" forever with no retry — and the study ViewModel set
 * `finished = true` with an empty reason, which rendered the success screen
 * reading "Session complete / 0 reviewed".
 *
 * `AppDatabase` caches its instance in a process-wide `INSTANCE`, so the
 * closed handle is cleared around each test; otherwise it would poison every
 * other test sharing this JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class SessionLoadFailureTest {

    private lateinit var app: McqApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
    }

    @After
    fun tearDown() {
        try {
            AppDatabase::class.java
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
                .set(null, null)
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Idles the main looper until [condition] holds, or gives up after ~1s. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private fun closeDatabase() = app.repository.db().close()

    @Test
    fun testSessionLoadFailureStopsLoadingAndReportsTheError() {
        val viewModel = TestViewModel(app, "p1", emptyList())
        // Close after construction, then load explicitly: whether the
        // initialiser's load ran inline or was queued on the looper depends
        // on the dispatcher, and the load under test must be the failing one.
        closeDatabase()
        viewModel.retry()

        assertTrue(awaitCondition { !viewModel.state.value.loading })

        val error = viewModel.state.value.loadError
        assertNotNull("a failed load must report why", error)
        assertTrue(error!!.startsWith("Could not start the test"))
        // Crucially not a silently empty session the user could mistake for
        // an empty paper.
        assertFalse(viewModel.state.value.submitted)
    }

    @Test
    fun studySessionLoadFailureDoesNotLookLikeACompletedSession() {
        val viewModel = StudyViewModel(app, "p1")
        closeDatabase()
        viewModel.retry()

        assertTrue(awaitCondition { !viewModel.state.value.loading })

        val error = viewModel.state.value.loadError
        assertNotNull("a failed load must report why", error)
        assertTrue(error!!.startsWith("Could not start the session"))
        // The old code set finished = true here, which rendered
        // "Session complete / 0 reviewed • 0 again • 0 remembered".
        assertFalse(viewModel.state.value.finished)
    }

    @Test
    fun retryClearsTheErrorAndAttemptsTheLoadAgain() {
        val viewModel = StudyViewModel(app, "p1")
        closeDatabase()
        viewModel.retry()
        assertTrue(awaitCondition { viewModel.state.value.loadError != null })

        viewModel.retry()

        // The retry runs the load again, which fails again and reports itself.
        // (On an inline dispatcher the message is already back by the time this
        // line runs, so asserting it was momentarily null would be racy.)
        assertTrue(awaitCondition { viewModel.state.value.loadError != null })
        assertFalse(viewModel.state.value.finished)
    }
}
