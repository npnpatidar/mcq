package com.mcqapp

import com.mcqapp.ui.settings.handleRestoreCompletion
import com.mcqapp.util.ImportTooLargeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore's busy-flag regression, tested at the seam that actually owns
 * the contract: [handleRestoreCompletion] must call onComplete on every
 * terminal path while still reporting errors and the success report.
 *
 * Pure JVM — no Robolectric, Application or Room. Every dependency is an
 * injected lambda. Dropping `finally { onComplete() }` fails the completion
 * assertions on both failure cases.
 */
class RestoreCompletionTest {

    private data class Completion(
        val errors: List<String>,
        val done: List<String>,
        val completed: Int,
        val successes: Int
    )

    private fun runCompletion(restore: suspend () -> String): Completion {
        val errors = mutableListOf<String>()
        val done = mutableListOf<String>()
        var completed = 0
        var successes = 0
        runBlocking {
            handleRestoreCompletion(
                onError = { errors += it },
                onDone = { done += it },
                onComplete = { completed++ },
                onSuccess = { successes++ },
                restore = restore
            )
        }
        return Completion(errors, done, completed, successes)
    }

    @Test
    fun successReportsAndCompletes() {
        val result = runCompletion { "restored 3 papers" }
        assertEquals(listOf("restored 3 papers"), result.done)
        assertTrue("a successful restore must not report an error: ${result.errors}",
            result.errors.isEmpty())
        assertEquals(1, result.completed)
        assertEquals(1, result.successes)
    }

    @Test
    fun tooLargeReportsErrorAndCompletes() {
        val result = runCompletion { throw ImportTooLargeException(limit = 256L * 1024 * 1024) }
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("restore limit"))
        assertTrue("onDone must not run on failure: ${result.done}", result.done.isEmpty())
        assertEquals("busy must be cleared on the too-large path", 1, result.completed)
        assertEquals("onSuccess must not run on failure", 0, result.successes)
    }

    @Test
    fun genericFailureReportsErrorAndCompletes() {
        val result = runCompletion { throw IllegalStateException("corrupt backup") }
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("Restore failed"))
        assertTrue("onDone must not run on failure: ${result.done}", result.done.isEmpty())
        assertEquals("busy must be cleared on the generic failure path", 1, result.completed)
        assertEquals("onSuccess must not run on failure", 0, result.successes)
    }
}
