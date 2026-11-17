package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.ui.sessions.SessionsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A failed session-history load must report the single user-facing string,
 * never the raw exception text. The detail is still logged in the ViewModel.
 *
 * Mirrors `SessionLoadFailureTest`: closing Room makes the load throw with a
 * technical message, and the process-wide database instance is cleared around
 * each test so a closed handle cannot poison the rest of the suite. DB-backed
 * Robolectric tests are authoritative on CI (x86_64), not this ARM64 host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class SessionsViewModelTest {

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

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun failedLoadReportsGenericErrorRatherThanRawExceptionMessage() {
        val viewModel = SessionsViewModel(app)
        app.repository.db().close()
        viewModel.refresh()

        assertTrue(awaitCondition { viewModel.state.value.error != null })
        // Old code stored e.message here (a Room/SQLite detail); the screen's
        // contract is the generic string only.
        assertEquals(
            app.getString(R.string.sessions_load_error),
            viewModel.state.value.error
        )
        assertFalse(viewModel.state.value.loading)
    }
}
