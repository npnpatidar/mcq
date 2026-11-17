package com.mcqapp.ui.settings

import com.mcqapp.domain.StorageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM pin of the storage card's state transitions. No Robolectric,
 * Application or Room: this is the logic that used to be tested through a
 * SettingsViewModel, whose construction repeatedly destabilised CI.
 */
class StorageLoadTest {

    private val message = "Storage could not be measured. Please try again."

    private fun report() = StorageInfo.Report(
        dbBytes = 4096L,
        papers = 2,
        questions = 10,
        attempts = 3,
        bookmarks = 1,
        perPaper = listOf(StorageInfo.PaperUsage("p1", "Paper 1", 10, 100L))
    )

    @Test
    fun successPublishesReportAndClearsError() = runBlocking {
        var seenReport: StorageInfo.Report? = null
        var seenError: String? = message
        val expected = report()

        loadStorageInto(
            load = { expected },
            onReport = { seenReport = it },
            onError = { seenError = it },
            errorMessage = message
        )

        assertSame("the measured report must reach the card", expected, seenReport)
        assertNull("a successful load must clear a previous error", seenError)
    }

    @Test
    fun failureClearsReportAndPublishesTheMessage() = runBlocking {
        var seenReport: StorageInfo.Report? = report()
        var seenError: String? = null
        var logged: Exception? = null
        val failure = IllegalStateException("database is locked")

        loadStorageInto(
            load = { throw failure },
            onReport = { seenReport = it },
            onError = { seenError = it },
            errorMessage = message,
            onException = { logged = it }
        )

        assertNull("a failed load must not leave a stale report on screen", seenReport)
        assertEquals("the card must show the error message", message, seenError)
        assertSame("the caller still gets the cause for logging", failure, logged)
    }

    @Test
    fun cancellationIsRethrownWithoutPublishingAnError() = runBlocking {
        var seenReport: StorageInfo.Report? = report()
        var seenError: String? = null
        val cancellation = CancellationException("leaving Settings")

        val thrown = runCatching {
            loadStorageInto(
                load = { throw cancellation },
                onReport = { seenReport = it },
                onError = { seenError = it },
                errorMessage = message
            )
        }.exceptionOrNull()

        assertSame("cancellation must not be swallowed as a load failure", cancellation, thrown)
        assertNotNull("cancellation must not clear the report", seenReport)
        assertNull("cancellation must not be shown as a storage error", seenError)
    }
}
