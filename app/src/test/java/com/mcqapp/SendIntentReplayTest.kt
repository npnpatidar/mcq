package com.mcqapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression suite for SEND-intent handling, pure JVM (no Activity, Compose or
 * Room). Each recorded payload stands for one `SharedImport.request` call.
 *
 * The original code handled SEND in `onCreate` unconditionally and double-
 * imported on rotation. Gating on `savedInstanceState == null` fixed that but
 * let a rotation cancel the in-flight copy and silently drop the share.
 * [SendIntentCoordinator] de-dupes by copy state while owning the copy on a
 * scope that outlives the Activity, so neither bug remains.
 */
class SendIntentReplayTest {

    // Unconfined so a non-suspending callback runs synchronously in tests.
    private fun newScope() = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private fun coordinator(
        scope: CoroutineScope,
        requests: MutableList<String>
    ) = SendIntentCoordinator<String>(scope) { requests += it }

    @Test
    fun rotationWithSameIntentRequestsSharedImportOnlyOnce() {
        val scope = newScope()
        val requests = mutableListOf<String>()
        val coordinator = coordinator(scope, requests)

        coordinator.onActivityCreate("share", isSend = true)
        coordinator.onActivityCreate("share", isSend = true) // rotation redelivers same intent

        assertEquals(listOf("share"), requests)
        scope.cancel()
    }

    @Test
    fun repeatedOnCreateNeverRequestsMoreThanOnce() {
        val scope = newScope()
        val requests = mutableListOf<String>()
        val coordinator = coordinator(scope, requests)

        // Even a second fresh-looking create cannot request twice.
        coordinator.onActivityCreate("share", isSend = true)
        coordinator.onActivityCreate("share", isSend = true)

        assertEquals(listOf("share"), requests)
        scope.cancel()
    }

    @Test
    fun distinctSendViaOnNewIntentStillRequests() {
        val scope = newScope()
        val requests = mutableListOf<String>()
        val coordinator = coordinator(scope, requests)

        coordinator.onActivityCreate("first", isSend = true)
        // A later, legitimate share must still produce a request.
        coordinator.onNewIntent("second", isSend = true)

        assertEquals(listOf("first", "second"), requests)
        scope.cancel()
    }

    @Test
    fun newIntentWithoutSendActionIsIgnored() {
        val scope = newScope()
        val requests = mutableListOf<String>()
        val coordinator = coordinator(scope, requests)

        coordinator.onNewIntent("share", isSend = false)

        assertEquals(0, requests.size)
        scope.cancel()
    }

    @Test
    fun coldStartWithoutSendNeverRequestsButALaterShareStillDoes() {
        val scope = newScope()
        val requests = mutableListOf<String>()
        val coordinator = coordinator(scope, requests)

        coordinator.onActivityCreate("share", isSend = false)
        assertEquals(0, requests.size)

        coordinator.onNewIntent("share", isSend = true)
        assertEquals(listOf("share"), requests)
        scope.cancel()
    }

    /**
     * The residual bug: a rotation while the file copy is still in flight.
     * The old lifecycleScope copy would be cancelled and the recreation
     * (savedInstanceState != null) skipped handling, losing the share. The
     * copy now belongs to a process-scoped owner, so rotation neither restarts
     * it nor cancels it, and the single request still lands in SharedImport.
     */
    @Test
    fun rotationDuringInFlightCopyDeliversShareOnceWithoutRestart() = runBlocking {
        val scope = newScope()
        val requests = mutableListOf<String>()
        val copyGate = CompletableDeferred<Unit>()
        var starts = 0
        val coordinator = SendIntentCoordinator<String>(scope) { payload ->
            starts += 1
            copyGate.await()
            requests += payload
        }

        // Cold start: the copy begins but has not reached SharedImport yet.
        coordinator.onActivityCreate("share", isSend = true)
        assertEquals(1, starts)
        assertEquals(0, requests.size)

        // Rotation redelivers the same SEND intent to a fresh Activity.
        coordinator.onActivityCreate("share", isSend = true)
        assertEquals("rotation must not restart the copy", 1, starts)

        // The copy survives and finishes; exactly one request is delivered.
        copyGate.complete(Unit)
        assertEquals(listOf("share"), requests)

        // Another rotation after completion must not import again.
        coordinator.onActivityCreate("share", isSend = true)
        assertEquals(listOf("share"), requests)

        scope.cancel()
    }
}
