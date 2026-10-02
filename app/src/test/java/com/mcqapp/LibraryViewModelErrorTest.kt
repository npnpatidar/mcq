package com.mcqapp

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.ui.library.LibraryViewModel
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A repository call that throws must reach the user instead of escaping
 * `viewModelScope`, which has no CoroutineExceptionHandler and would take
 * the process down. Closing the database is a faithful way to make every
 * subsequent DAO call fail.
 *
 * `AppDatabase` caches its instance in a process-wide `INSTANCE`, so the
 * closed handle is cleared around each test; otherwise it would poison every
 * other test sharing this JVM. Clearing it (rather than restoring the saved
 * reference) matters: the saved reference *is* the handle just closed, so
 * putting it back would hand the next test a closed database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = McqApplication::class)
class LibraryViewModelErrorTest {

    private lateinit var viewModel: LibraryViewModel

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>() as McqApplication
        viewModel = LibraryViewModel(app)
        // Closed after construction: the ViewModel subscribes to DAO flows in
        // its initialiser, and it is the *mutations* under test that must fail.
        app.repository.db().close()
    }

    @After
    fun tearDown() {
        // Cleared first and unconditionally: idling the looper while the
        // database is closed makes the ViewModel's flow collectors throw, and a
        // skipped reset would leak the closed handle to the next test.
        try {
            clearInstance()
        } finally {
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun clearInstance() {
        AppDatabase::class.java
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .set(null, null)
    }

    /** Runs the main looper until [viewModel] reports an error, or gives up. */
    private fun awaitError(): String? {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.exportError.value?.let { return it }
            Thread.sleep(5)
        }
        return viewModel.exportError.value
    }

    @Test
    fun deletePaperFailureIsReportedInsteadOfCrashing() {
        viewModel.deletePaper("p1")
        val error = awaitError()
        assertNotNull("a failed delete must surface an error", error)
        assertTrue(error!!.startsWith("Delete failed"))
    }

    @Test
    fun deleteCategoryFailureIsReportedInsteadOfCrashing() {
        viewModel.deleteCategory("c1")
        val error = awaitError()
        assertNotNull("a failed category delete must surface an error", error)
        assertTrue(error!!.startsWith("Delete failed"))
    }

    @Test
    fun addCategoryFailureIsReportedInsteadOfCrashing() {
        viewModel.addCategory("p1", "Algebra", null)
        val error = awaitError()
        assertNotNull("a failed add must surface an error", error)
        assertTrue(error!!.startsWith("Could not add the category"))
    }

    @Test
    fun addPaperFailureIsReportedInsteadOfCrashing() {
        viewModel.addPaper("Paper", "desc", 30, 0.0)
        val error = awaitError()
        assertNotNull("a failed add must surface an error", error)
        assertTrue(error!!.startsWith("Could not add the paper"))
    }

    @Test
    fun theErrorMessageKeepsTheUnderlyingCause() {
        viewModel.deletePaper("p1")
        val error = awaitError()!!
        // An opaque "something went wrong" would leave the user unable to tell
        // a full disk from a locked database.
        assertTrue("message was: $error", error.length > "Delete failed: ".length)
    }
}
