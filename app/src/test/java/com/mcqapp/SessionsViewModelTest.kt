package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.stats.StatsAggregator
import com.mcqapp.ui.sessions.SessionsViewModel
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Both the loader and the dispatcher are injected: the loader throws a
 * technical message directly and runs on Main.immediate, so the failure state
 * is published synchronously with no DB-close or looper-pumping timing to race
 * on CI. Robolectric only supplies an Application context for string resolution.
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

    @Test
    fun failedLoadReportsGenericErrorRatherThanRawExceptionMessage() {
        val viewModel = SessionsViewModel(app)
        val raw = "sqlite detail: disk I/O error"
        viewModel.load(
            range = StatsAggregator.Range.D30,
            loader = { throw IllegalStateException(raw) },
            dispatcher = Dispatchers.Main.immediate
        )

        val error = viewModel.state.value.error
        assertNotNull("a failed load must report an error", error)
        // Old code stored e.message here (a Room/SQLite detail); the screen's
        // contract is the generic string only.
        assertEquals(app.getString(R.string.sessions_load_error), error)
        assertFalse("raw loader message must not leak into UI state", error!!.contains(raw))
        assertFalse(viewModel.state.value.loading)
    }
}
