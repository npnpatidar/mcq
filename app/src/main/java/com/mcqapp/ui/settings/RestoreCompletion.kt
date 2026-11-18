package com.mcqapp.ui.settings

import com.mcqapp.util.ImportTooLargeException
import com.mcqapp.util.Logger

/**
 * The restore's error/completion handling, deliberately independent of the
 * ViewModel so it can be exercised on a plain JVM: the fallible work, the
 * success-time side effect and the way an error is rendered are injected, while
 * the failure reporting and logging live here so every restore is handled
 * identically.
 *
 * No user-facing text is built here. [tooLargeResId]/[tooLargeArgs] and
 * [genericResId] are plain resource ids and arguments passed in by the caller;
 * [renderError] resolves them through the caller's localized resource lookup.
 * The exception itself goes to [Logger] only, never to the learner.
 *
 * [onComplete] runs on every terminal path — success, either failure or
 * cancellation — so the caller can always clear its busy flag. [onError]
 * still reports the failure, so clearing busy does not hide the error dialog.
 */
internal suspend fun handleRestoreCompletion(
    onError: (String) -> Unit,
    onDone: (String) -> Unit,
    onComplete: () -> Unit,
    onSuccess: suspend () -> Unit,
    restore: suspend () -> String,
    tooLargeResId: Int,
    tooLargeArgs: List<Any>,
    genericResId: Int,
    renderError: (Int, List<Any>) -> String
) {
    try {
        val report = restore()
        onSuccess()
        Logger.i("SETTINGS", "Backup restored: $report")
        onDone(report)
    } catch (e: ImportTooLargeException) {
        Logger.e("SETTINGS", "Restore failed", e)
        onError(renderError(tooLargeResId, tooLargeArgs))
    } catch (e: Exception) {
        Logger.e("SETTINGS", "Restore failed", e)
        onError(renderError(genericResId, emptyList()))
    } finally {
        onComplete()
    }
}
