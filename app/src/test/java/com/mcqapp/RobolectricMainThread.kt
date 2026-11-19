package com.mcqapp

import android.os.Looper
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/**
 * Runs [block] off the Robolectric main thread while this (test) thread keeps
 * pumping the paused main looper. A coroutine that must resume on
 * `Dispatchers.Main` can therefore make progress even while this helper waits
 * for the off-main work.
 *
 * This is deliberately not `runBlocking` on the test thread: that would block
 * the very looper a Main-dispatched continuation needs. The worker owns the
 * suspending work; the test thread owns the looper until the work completes.
 *
 * Never call this from a thread other than Robolectric's main/test thread for
 * a test that relies on looper pumping.
 *
 * **The block is never cancelled, not at the deadline and not by interrupt.**
 * The wait is bounded: past [timeoutMs] (plus a small margin for work that
 * cannot observe time) this throws, but the worker is left running as a
 * daemon. Cancelling would be actively harmful for DataStore work:
 * `updateData` wraps the caller's own coroutine context into the actor
 * message and runs the transform under it inside the single serialized write
 * actor (androidx datastore-core `DataStoreImpl.updateData`), so a
 * cancellation that lands mid-edit is delivered inside that one loop. The
 * actor then stops consuming and every later DataStore call in the process
 * parks forever — the whole JVM idle, one test stuck in `runBlocking`. That
 * is the exact wedge this suite has hit repeatedly under loaded CI runners
 * (jstack: `Fsrs7SelectionTest.setUp` inside `dataStore.edit`, nothing else
 * running anywhere). Failing the test while leaving the work uncancelled
 * reports the hang without poisoning every test after it.
 */
internal fun <T> runOffMainWhilePumping(
    timeoutMs: Long = 30_000,
    condition: String,
    block: suspend () -> T
): T {
    require(timeoutMs > 0) { "timeoutMs must be positive, got $timeoutMs" }

    val outcome = AtomicReference<WorkOutcome<T>?>(null)
    val worker = Thread({
        outcome.set(
            try {
                WorkOutcome.Success(runBlocking { block() })
            } catch (e: Throwable) {
                WorkOutcome.Failure(e)
            }
        )
    }, "robolectric-off-main")
    worker.isDaemon = true
    worker.start()

    // No interrupt at the deadline: an interrupt cancels the worker's
    // runBlocking, and a DataStore edit cancelled mid-flight poisons the
    // process-wide store for every later test (see the KDoc above). The
    // daemon worker is abandoned instead; a genuinely wedged block is the
    // task-level timeout's problem, not the next test's.
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (outcome.get() == null) {
        shadowOf(Looper.getMainLooper()).idle()
        if (outcome.get() != null) break
        if (System.nanoTime() >= deadline) {
            throw AssertionError(
                "timed out after ${timeoutMs}ms waiting for $condition " +
                    "(work left running; it is never cancelled)"
            )
        }
        Thread.sleep(1)
    }

    return when (val result = outcome.get()) {
        is WorkOutcome.Success -> result.value
        is WorkOutcome.Failure -> {
            // Preserve the original type and message: some tests deliberately
            // assert the production exception. The helper's context is added
            // as suppressed detail instead of replacing that contract.
            result.error.addSuppressed(
                AssertionError("failed while waiting for $condition")
            )
            throw result.error
        }
        null -> error("missing work outcome after waiting for $condition")
    }
}

private sealed interface WorkOutcome<out T> {
    data class Success<T>(val value: T) : WorkOutcome<T>
    data class Failure(val error: Throwable) : WorkOutcome<Nothing>
}
