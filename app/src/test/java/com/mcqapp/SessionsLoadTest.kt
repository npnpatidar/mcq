package com.mcqapp

import com.mcqapp.ui.sessions.loadSessionsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Sessions load transition, exercised as a pure coroutine: no Application,
 * Robolectric or Room involved. In particular, a technical exception message
 * must never reach the UI state — only the generic string does.
 */
class SessionsLoadTest {

    private companion object {
        const val GENERIC = "Sessions could not be loaded."
    }

    private class Recorder<T> {
        var starts = 0
        var success: T? = null
        var failure: String? = null
        var logged: Throwable? = null
    }

    @Test
    fun successPublishesDataAndNoFailure() = runBlocking {
        val recorder = Recorder<String>()
        loadSessionsState(
            onStart = { recorder.starts++ },
            onSuccess = { recorder.success = it },
            onFailure = { recorder.failure = it },
            genericMessage = GENERIC,
            onLog = { recorder.logged = it },
            loader = { "payload" }
        )

        assertEquals(1, recorder.starts)
        assertEquals("payload", recorder.success)
        assertNull(recorder.failure)
        assertNull(recorder.logged)
    }

    @Test
    fun failurePublishesOnlyTheGenericMessageAndLogsTheDetail() = runBlocking {
        val recorder = Recorder<String>()
        val raw = "sqlite detail: disk I/O error"
        loadSessionsState(
            onStart = { recorder.starts++ },
            onSuccess = { recorder.success = it },
            onFailure = { recorder.failure = it },
            genericMessage = GENERIC,
            onLog = { recorder.logged = it },
            loader = { throw IllegalStateException(raw) }
        )

        assertEquals(1, recorder.starts)
        assertNull(recorder.success)
        // Old code passed e.message here; both assertions fail on that.
        assertEquals(GENERIC, recorder.failure)
        assertFalse(recorder.failure!!.contains(raw))
        assertNotNull(recorder.logged)
        assertTrue(recorder.logged is IllegalStateException)
    }

    @Test
    fun cancellationIsRethrownWithoutSuccessFailureOrLog() = runBlocking {
        val recorder = Recorder<String>()
        var caught: Throwable? = null
        try {
            loadSessionsState(
                onStart = { recorder.starts++ },
                onSuccess = { recorder.success = it },
                onFailure = { recorder.failure = it },
                genericMessage = GENERIC,
                onLog = { recorder.logged = it },
                loader = { throw CancellationException("cancelled") }
            )
            fail("a cancelled load must rethrow, not publish a result")
        } catch (e: CancellationException) {
            caught = e
        }

        assertNotNull(caught)
        assertEquals(1, recorder.starts)
        assertNull(recorder.success)
        assertNull(recorder.failure)
        assertNull(recorder.logged)
    }
}
