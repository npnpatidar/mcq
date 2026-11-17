package com.mcqapp.ui.settings

import com.mcqapp.domain.StorageInfo
import kotlinx.coroutines.CancellationException

/**
 * Applies one storage measurement to the card's two pieces of state.
 *
 * Kept top-level and free of Android/Room so the state transitions can be
 * tested on the JVM: the caller supplies the already-resolved message and the
 * callbacks that write its state. Cancellation is never turned into an error
 * message — leaving Settings cancels the load and must not repaint the card.
 */
internal suspend fun loadStorageInto(
    load: suspend () -> StorageInfo.Report,
    onReport: (StorageInfo.Report?) -> Unit,
    onError: (String?) -> Unit,
    errorMessage: String,
    onException: (Exception) -> Unit = {}
) {
    try {
        val report = load()
        onError(null)
        onReport(report)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onException(e)
        // Clear any stale report so the error is the single state the card
        // renders; a failed retry must not leave old figures on screen.
        onReport(null)
        onError(errorMessage)
    }
}
