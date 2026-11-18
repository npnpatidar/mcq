package com.mcqapp

import com.mcqapp.ui.settings.handleRestoreCompletion
import com.mcqapp.util.ImportTooLargeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore's busy-flag regression, tested at the seam that actually owns
 * the contract: [handleRestoreCompletion] must call onComplete on every
 * terminal path while still reporting errors and the success report.
 *
 * Pure JVM — no Robolectric, Application or Room. Every dependency is an
 * injected lambda. Dropping `finally { onComplete() }` fails the completion
 * assertions on both failure cases. The error copy is injected as plain res
 * ids plus a resolver standing in for the caller's localized `msg()`, so no
 * exception detail can reach the learner through this seam.
 */
class RestoreCompletionTest {

    private data class Completion(
        val errors: List<String>,
        val errorIds: List<Int>,
        val errorArgs: List<List<Any>>,
        val done: List<String>,
        val completed: Int,
        val successes: Int
    )

    private fun runCompletion(restore: suspend () -> String): Completion {
        val errors = mutableListOf<String>()
        val errorIds = mutableListOf<Int>()
        val errorArgs = mutableListOf<List<Any>>()
        val done = mutableListOf<String>()
        var completed = 0
        var successes = 0
        runBlocking {
            handleRestoreCompletion(
                onError = { errors += it },
                onDone = { done += it },
                onComplete = { completed++ },
                onSuccess = { successes++ },
                restore = restore,
                tooLargeResId = R.string.final_restore_too_large,
                tooLargeArgs = listOf(256L),
                genericResId = R.string.final_restore_failed,
                // Test double for getString(): stands in for the ViewModel's
                // localized msg() and lets this stay a plain-JVM test.
                renderError = { id, args ->
                    errorIds += id
                    errorArgs += args
                    when (id) {
                        R.string.final_restore_too_large ->
                            "That backup is over the 256 MB restore limit — it may be corrupt."
                        else -> "Restore failed: please try again"
                    }
                }
            )
        }
        return Completion(errors, errorIds, errorArgs, done, completed, successes)
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
        assertEquals(listOf(R.string.final_restore_too_large), result.errorIds)
        assertEquals(listOf(256L), result.errorArgs.single())
        assertTrue("onDone must not run on failure: ${result.done}", result.done.isEmpty())
        assertEquals("busy must be cleared on the too-large path", 1, result.completed)
        assertEquals("onSuccess must not run on failure", 0, result.successes)
    }

    @Test
    fun genericFailureReportsErrorAndCompletes() {
        val result = runCompletion { throw IllegalStateException("corrupt backup") }
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("Restore failed"))
        assertEquals(listOf(R.string.final_restore_failed), result.errorIds)
        assertTrue(result.errorArgs.single().isEmpty())
        assertTrue("onDone must not run on failure: ${result.done}", result.done.isEmpty())
        assertEquals("busy must be cleared on the generic failure path", 1, result.completed)
        assertEquals("onSuccess must not run on failure", 0, result.successes)
    }

    @Test
    fun exceptionDetailNeverReachesTheReportedError() {
        val result = runCompletion { throw IllegalStateException("SECRET-DISK-DETAIL") }
        assertFalse("raw cause leaked: ${result.errors}",
            result.errors.any { "SECRET-DISK-DETAIL" in it || "Exception" in it })
    }
}
