package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.Paper
import com.mcqapp.ui.test.TestViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * The test-start duration picker rides the route into the ViewModel; these
 * pin the three outcomes the library dialog can produce.
 *
 * The paper is stored with 20 minutes (the sample bank's value), so the
 * assertions can tell the three sources apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class TestDurationOverrideTest {

    private lateinit var app: McqApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
        runBlocking {
            app.repository.savePaper(
                Paper(
                    id = "p1",
                    title = "Timed paper",
                    description = "",
                    durationMinutes = 20,
                    negativeMarking = 0.0
                )
            )
        }
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
    fun noOverrideRunsThePaperDuration() {
        val viewModel = TestViewModel(app, "p1", emptyList())
        assertTrue(awaitCondition { !viewModel.state.value.loading })
        assertEquals(20 * 60, viewModel.state.value.totalSeconds)
        assertEquals(20 * 60, viewModel.state.value.remainingSeconds)
    }

    @Test
    fun anExplicitOverrideReplacesThePaperDuration() {
        val viewModel = TestViewModel(app, "p1", emptyList(), durationMinutes = 45)
        assertTrue(awaitCondition { !viewModel.state.value.loading })
        assertEquals(45 * 60, viewModel.state.value.totalSeconds)
        assertEquals(45 * 60, viewModel.state.value.remainingSeconds)
    }

    @Test
    fun anOverrideOfZeroRunsUntimedInsteadOfThePaperDuration() {
        val viewModel = TestViewModel(app, "p1", emptyList(), durationMinutes = 0)
        assertTrue(awaitCondition { !viewModel.state.value.loading })
        assertEquals(0, viewModel.state.value.totalSeconds)
        assertEquals(0, viewModel.state.value.remainingSeconds)
    }
}
