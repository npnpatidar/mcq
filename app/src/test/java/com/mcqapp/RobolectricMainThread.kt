package com.mcqapp

import android.os.Looper
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * a test that relies on looper pumping. Both the suspending work and the
 * fallback wait are bounded, and a timeout names [condition].
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
                WorkOutcome.Success(
                    runBlocking {
                        try {
                            withTimeout(timeoutMs) { block() }
                        } catch (e: TimeoutCancellationException) {
                            throw AssertionError(
                                "timed out after ${timeoutMs}ms waiting for $condition"
                            ).also { it.initCause(e) }
                        }
                    }
                )
            } catch (e: Throwable) {
                WorkOutcome.Failure(e)
            }
        )
    }, "robolectric-off-main")
    worker.isDaemon = true
    worker.start()

    // The coroutine timeout is the normal bound. Keep a small outer bound as
    // well for work that does not cooperate with cancellation (for example a
    // native SQLite call already in progress).
    val hardTimeoutMs = timeoutMs + 2_000
    val deadline = System.nanoTime() + hardTimeoutMs * 1_000_000
    while (outcome.get() == null) {
        shadowOf(Looper.getMainLooper()).idle()
        if (outcome.get() != null) break
        if (System.nanoTime() >= deadline) {
            worker.interrupt()
            throw AssertionError(
                "timed out after ${hardTimeoutMs}ms waiting for $condition"
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
