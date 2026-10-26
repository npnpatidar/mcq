package com.mcqapp.domain

/** Outcome of persisting a finished test session. */
sealed interface SubmissionOutcome<out T> {
    data class Saved<T>(val value: T) : SubmissionOutcome<T>
    data class Failed(val message: String?) : SubmissionOutcome<Nothing>
}

/**
 * Persists a finished attempt and then drops the resume snapshot.
 *
 * The order is the whole point. The snapshot is the only copy of an
 * in-progress exam, so clearing it before the attempt is durable loses the
 * user's work on a storage error or a process death. Clearing afterwards is
 * allowed to fail: the attempt is already durable, and a snapshot that
 * outlives its own session is defended against at offer time — the resume
 * check clears it when the paper's latest attempt was finished after the
 * snapshot was written (consolidated_bugs.md D8) — but it is *not* harmless
 * by itself, and this function does not pretend otherwise.
 *
 * A failed save returns [SubmissionOutcome.Failed] and never touches the
 * snapshot, so the caller can keep the session on screen and retry.
 */
suspend fun <T> submitAttempt(
    save: suspend () -> T,
    clearSnapshot: suspend () -> Unit,
    onClearFailure: (Throwable) -> Unit = {}
): SubmissionOutcome<T> {
    val saved = try {
        save()
    } catch (e: Exception) {
        return SubmissionOutcome.Failed(e.message)
    }
    try {
        clearSnapshot()
    } catch (e: Exception) {
        onClearFailure(e)
    }
    return SubmissionOutcome.Saved(saved)
}
