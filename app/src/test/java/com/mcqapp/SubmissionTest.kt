package com.mcqapp

import com.mcqapp.domain.SubmissionOutcome
import com.mcqapp.domain.submitAttempt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SubmissionTest {

    @Test
    fun savesBeforeClearingTheSnapshot() = runBlocking {
        val order = mutableListOf<String>()
        val outcome = submitAttempt(
            save = { order += "save"; 42L },
            clearSnapshot = { order += "clear" }
        )
        assertEquals(listOf("save", "clear"), order)
        assertEquals(SubmissionOutcome.Saved(42L), outcome)
    }

    @Test
    fun aFailedSaveNeverClearsTheSnapshot() = runBlocking {
        var cleared = false
        val outcome = submitAttempt<Long>(
            save = { throw IOException("disk full") },
            clearSnapshot = { cleared = true }
        )
        // The snapshot is the only copy of the exam: dropping it here is
        // exactly how a finished session used to vanish.
        assertEquals(false, cleared)
        assertTrue(outcome is SubmissionOutcome.Failed)
        assertEquals("disk full", (outcome as SubmissionOutcome.Failed).message)
    }

    @Test
    fun aFailedClearStillReportsTheSavedAttempt() = runBlocking {
        var reported: Throwable? = null
        val outcome = submitAttempt(
            save = { 7L },
            clearSnapshot = { throw IOException("stale lock") },
            onClearFailure = { reported = it }
        )
        // The attempt is durable, so the user must still get their results.
        assertEquals(SubmissionOutcome.Saved(7L), outcome)
        assertEquals("stale lock", reported?.message)
    }
}
