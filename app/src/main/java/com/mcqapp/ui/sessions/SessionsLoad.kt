package com.mcqapp.ui.sessions

import kotlinx.coroutines.CancellationException

/**
 * The state transition for one session-history load, with no Android, Room or
 * ViewModel dependency so it can be exercised as a pure coroutine.
 *
 * On failure only [genericMessage] is published; the exception itself is sent
 * to [onLog], never to [onFailure]. Cancellation is not a load failure and is
 * rethrown without any of the result callbacks.
 */
internal suspend fun <T> loadSessionsState(
    onStart: () -> Unit,
    onSuccess: (T) -> Unit,
    onFailure: (String) -> Unit,
    genericMessage: String,
    onLog: (Throwable) -> Unit,
    loader: suspend () -> T
) {
    onStart()
    try {
        val data = loader()
        onSuccess(data)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onLog(e)
        onFailure(genericMessage)
    }
}
