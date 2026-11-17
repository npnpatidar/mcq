package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.ui.browse.BrowseViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression: `selectAllInScope` used to catch a failed filtered-id read and
 * only log it — with no error message, selection mode stayed on with zero
 * ticks. Closing the database is a faithful way to make that read fail (the
 * `LibraryViewModelErrorTest` precedent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class BrowseSelectAllFailureTest {

    private lateinit var viewModel: BrowseViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
        viewModel = BrowseViewModel(app, "p1")
        // Closed after construction: the ViewModel subscribes to DAO flows in
        // its initialiser; the select-all read under test must be the failure.
        app.repository.db().close()
    }

    @After
    fun tearDown() {
        // Cleared first, unconditionally: idling the looper while the database
        // is closed makes the ViewModel's flow collectors throw, and a skipped
        // reset would leak the closed handle to the next test.
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
    fun aFailedSelectAllSurfacesAnErrorInsteadOfSilentlyTickingNothing() {
        var result: Set<String>? = null
        viewModel.selectAllInScope { result = it }

        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (viewModel.error.value != null && result != null) return@repeat
            Thread.sleep(5)
        }

        val error = viewModel.error.value
        assertNotNull("a failed Select all must surface an error", error)
        assertEquals(R.string.action_failed, error!!.titleRes)
        assertTrue(
            "error message should name Select all, was: ${error.message}",
            error.message.contains("select all", ignoreCase = true)
        )
        // The callback still receives an empty set, as before — the difference
        // is that the failure is now visible to the user.
        assertEquals(emptySet<String>(), result)
    }
}
