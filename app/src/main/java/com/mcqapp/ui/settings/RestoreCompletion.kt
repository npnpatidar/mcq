package com.mcqapp.ui.settings

import com.mcqapp.util.ImportTooLargeException
import com.mcqapp.util.Logger

/**
 * The restore's error/completion handling, deliberately independent of the
 * ViewModel so it can be exercised on a plain JVM: the fallible work and the
 * success-time side effect are injected, while the failure copy and logging
 * live here so every restore reports a failure identically.
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
    restore: suspend () -> String
) {
    try {
        val report = restore()
        onSuccess()
        Logger.i("SETTINGS", "Backup restored: $report")
        onDone(report)
    } catch (e: ImportTooLargeException) {
        Logger.e("SETTINGS", "Restore failed", e)
        val limitMb = com.mcqapp.data.SafeZip.MAX_TOTAL_BYTES / (1024 * 1024)
        onError("That backup is over the $limitMb MB restore limit — it may be corrupt.")
    } catch (e: Exception) {
        Logger.e("SETTINGS", "Restore failed", e)
        onError("Restore failed: ${e.message}")
    } finally {
        onComplete()
    }
}
