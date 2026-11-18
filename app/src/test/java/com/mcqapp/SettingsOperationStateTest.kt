package com.mcqapp

import com.mcqapp.ui.settings.SettingsOperationError
import com.mcqapp.ui.settings.SettingsOperationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SE1: Backup & data operation state is owned by the ViewModel, so an Activity
 * recreation in the middle of an operation cannot strand the new composition
 * behind a stale `busy=true` with no result dialog.
 *
 * Pure JVM — no Robolectric, Application or Room. [SettingsOperationState] is
 * the real holder the ViewModel delegates to and [SettingsOperationState.runRestore]
 * drives the same [com.mcqapp.ui.settings.handleRestoreCompletion] seam, so the
 * assertions exercise shipped code rather than a look-alike. "Recreation" is
 * simulated by attaching a second, fresh collector to the same holder after the
 * operation has begun — exactly what the recreated screen's collectAsState does.
 */
class SettingsOperationStateTest {

    private class CompositionSnapshot(
        var busy: Boolean? = null,
        var error: SettingsOperationError? = null,
        var report: String? = null
    )

    private fun renderError(resId: Int, args: List<Any>): String =
        if (resId == R.string.final_restore_too_large) {
            "That backup is over the ${args.firstOrNull()} MB restore limit — it may be corrupt."
        } else {
            "Restore failed: please try again"
        }

    /** A brand-new composition attaching to the surviving holder after recreation. */
    private suspend fun CoroutineScope.attachRecreatedComposition(
        operations: SettingsOperationState
    ): Pair<CompositionSnapshot, Job> {
        val snapshot = CompositionSnapshot()
        // Launch collectors directly on this receiver, not inside a wrapper
        // coroutine: a single yield could dispatch the wrapper but leave the
        // nested collectors unstarted, so the recreated snapshot stayed null.
        // A shared parent Job is the handle that cancels all three.
        val observer = SupervisorJob()
        launch(observer) { operations.busy.collect { snapshot.busy = it } }
        launch(observer) { operations.error.collect { snapshot.error = it } }
        launch(observer) { operations.restoreReport.collect { snapshot.report = it } }
        // Yield twice: once to dispatch the collectors, once for StateFlow's
        // initial emission to reach each of them.
        yield()
        yield()
        return snapshot to observer
    }

    @Test
    fun completionAfterRecreationClearsBusyAndPublishesReport() = runBlocking {
        val operations = SettingsOperationState()
        val gate = CompletableDeferred<Unit>()
        val operation = launch {
            operations.runRestore(
                errorTitleRes = R.string.restore_failed,
                tooLargeResId = R.string.final_restore_too_large,
                tooLargeArgs = listOf(256L),
                genericResId = R.string.final_restore_failed,
                renderError = { resId, args -> renderError(resId, args) },
                restore = {
                    gate.await()
                    "restored 3 papers"
                }
            )
        }
        yield()
        assertTrue("the operation must start busy", operations.busy.value)

        val (recreated, observer) = attachRecreatedComposition(operations)
        assertTrue(
            "the new composition must see the in-flight operation",
            recreated.busy == true
        )

        gate.complete(Unit)
        operation.join()
        yield()
        observer.cancel()

        assertFalse(
            "the recreated screen must be re-enabled after completion",
            recreated.busy == true
        )
        assertEquals("restored 3 papers", recreated.report)
        assertNull("a successful restore has no error", recreated.error)
    }

    @Test
    fun failedRestoreAfterRecreationClearsBusyAndShowsFriendlyError() = runBlocking {
        val operations = SettingsOperationState()
        val gate = CompletableDeferred<Unit>()
        val operation = launch {
            operations.runRestore(
                errorTitleRes = R.string.restore_failed,
                tooLargeResId = R.string.final_restore_too_large,
                tooLargeArgs = listOf(256L),
                genericResId = R.string.final_restore_failed,
                renderError = { resId, args -> renderError(resId, args) },
                restore = {
                    gate.await()
                    throw IllegalStateException("SECRET-BACKUP-DETAIL")
                }
            )
        }
        yield()
        assertTrue("the failing operation must start busy", operations.busy.value)

        val (recreated, observer) = attachRecreatedComposition(operations)
        assertTrue(
            "the new composition must see the in-flight operation",
            recreated.busy == true
        )

        gate.complete(Unit)
        operation.join()
        yield()
        observer.cancel()

        assertFalse(
            "a failed restore must still re-enable the recreated screen",
            recreated.busy == true
        )
        val error = recreated.error
        assertEquals(R.string.restore_failed, error?.titleRes)
        assertEquals("Restore failed: please try again", error?.message)
        assertFalse(
            "raw exception detail must not leak: ${error?.message}",
            error?.message?.contains("SECRET-BACKUP-DETAIL") == true
        )
        assertNull("a failed restore publishes no success report", recreated.report)
    }
}
