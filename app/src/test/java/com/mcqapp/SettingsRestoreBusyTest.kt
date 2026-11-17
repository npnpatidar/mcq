package com.mcqapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.ui.settings.SettingsViewModel
import com.mcqapp.util.ImportTooLargeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * H4 regression: a failed/corrupt restore used to call only `onError`; the
 * screen's `busy` flag was cleared only in the success callback, so every
 * Backup & data button stayed disabled forever after a failed restore (and
 * `busy` was `rememberSaveable`, so it survived navigation too).
 *
 * The screen mirrors its `busy` flag with a local variable that [onComplete]
 * clears; the fix guarantees that callback on every terminal path. Both catch
 * paths must clear it while still reporting the error through [onError].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class SettingsRestoreBusyTest {

    private fun viewModel() = SettingsViewModel(
        ApplicationProvider.getApplicationContext<Context>() as McqApplication
    )

    /** Mirrors the screen: busy goes true before restore, onComplete clears it. */
    private fun runRestore(restore: suspend () -> String): RestoreOutcome {
        val vm = viewModel()
        var busy = false
        val reported = mutableListOf<String>()
        val done = mutableListOf<String>()
        busy = true // the screen sets this before calling restoreBackup
        runBlocking {
            vm.restoreBackupHandling(
                onError = { reported += it },
                onDone = { done += it },
                onComplete = { busy = false },
                restore = restore
            )
        }
        return RestoreOutcome(busy, reported, done)
    }

    private fun runFailingRestore(failure: Throwable): RestoreOutcome =
        runRestore { throw failure }

    private data class RestoreOutcome(
        val busy: Boolean,
        val reported: List<String>,
        val done: List<String>
    )

    @Test
    fun busyIsClearedAndReportDeliveredOnSuccess() {
        val result = runRestore { "restored 3 papers" }
        assertFalse("busy must be cleared after a successful restore", result.busy)
        assertEquals(emptyList<String>(), result.reported)
        assertEquals(listOf("restored 3 papers"), result.done)
    }

    @Test
    fun busyIsClearedWhenRestoreIsTooLarge() {
        val result = runFailingRestore(ImportTooLargeException(limit = 256L * 1024 * 1024))
        assertFalse("busy must be cleared after the too-large restore failure", result.busy)
        assertEquals(1, result.reported.size)
        assertTrue(
            "the error dialog text must still be reported",
            result.reported.single().contains("restore limit")
        )
    }

    @Test
    fun busyIsClearedWhenRestoreFailsGenerically() {
        val result = runFailingRestore(IllegalStateException("corrupt backup"))
        assertFalse("busy must be cleared after the generic restore failure", result.busy)
        assertEquals(1, result.reported.size)
        assertTrue(
            "the error dialog text must still be reported",
            result.reported.single().contains("Restore failed")
        )
    }
}
