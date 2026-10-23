package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ReviewGrade
import com.mcqapp.ui.study.StudyViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A restart whose pull fails must surface its error, not spin forever.
 *
 * `restart()` sets `loading = true` before it pulls, and the screen's `when`
 * checks that flag first — so a catch block that records only `loadError`
 * leaves the card already nulled under a spinner no retry can reach. Closing
 * the database is the faithful way to make the restart's pull fail (the same
 * trick [LibraryViewModelErrorTest] uses for its mutations): every subsequent
 * DAO call fails.
 *
 * `AppDatabase` caches its instance in a process-wide `INSTANCE`, so the closed
 * handle is cleared around the test like there; and this test *must not* live
 * in [StudyViewModelPullTest], whose shared `@After` cleanup calls the
 * repository the test has just closed.
 *
 * Robolectric + the real repository + the real database, so like every
 * DB-backed test it runs on CI's x86_64 runners and not on the ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class StudyViewModelRestartFailureTest {

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as McqApplication

    @After
    fun tearDown() {
        // Cleared first and unconditionally: a skipped reset would leak the
        // closed handle to the next test, the same reason the error test does
        // this before idling the looper.
        AppDatabase::class.java
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .set(null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Runs the main looper until [condition] holds, or gives up. */
    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun aFailedRestartSurfacesItsErrorInsteadOfSpinning() = runBlocking {
        // One graduated, one fresh card, so the session has something to pull.
        app.repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        app.repository.saveQuestion(
            com.mcqapp.domain.Question(
                id = "q1",
                categoryId = "c1",
                text = "Q?",
                options = listOf(
                    com.mcqapp.domain.QuestionOption("q1-a", "A"),
                    com.mcqapp.domain.QuestionOption("q1-b", "B")
                ),
                correctOptionIds = setOf("q1-a")
            )
        )
        app.repository.recordStudyReview("p1", "q1", ReviewGrade.GOOD)

        val viewModel = StudyViewModel(app, "p1")
        assertTrue("state=${viewModel.state.value}", awaitCondition { viewModel.state.value.current != null })

        // Close the shared handle so the restart's pull fails the way a locked
        // or full disk would, then restart.
        app.repository.db().close()
        viewModel.restart()

        // The failure must surface: loading clears so the screen's error
        // branch is reachable, the error is set, and no stale card lingers.
        assertTrue(
            "state=${viewModel.state.value} — a failed restart must reach the catch block",
            awaitCondition { !viewModel.state.value.loading && viewModel.state.value.loadError != null }
        )
        assertFalse(viewModel.state.value.loading)
        assertNotNull(viewModel.state.value.loadError)
        assertTrue(viewModel.state.value.loadError!!.startsWith("Could not start the session"))
        assertEquals(null, viewModel.state.value.current)
    }
}
