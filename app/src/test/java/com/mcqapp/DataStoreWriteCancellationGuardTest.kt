package com.mcqapp

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Never cancel a DataStore write mid-flight.
 *
 * `updateData` wraps the caller's own coroutine context into the actor message
 * and runs the transform under it inside the single serialized write actor
 * (androidx datastore-core `DataStoreImpl.updateData`), so a caller cancelled
 * mid-edit delivers its CancellationException inside that one loop. The actor
 * then stops consuming and every later DataStore call in the process parks
 * forever: all worker threads idle, the test thread stuck in `runBlocking`.
 * That is the exact wedge this suite hit repeatedly on loaded CI runners
 * (jstack: Fsrs7SelectionTest.setUp inside a dataStore edit, the whole JVM
 * otherwise parked).
 *
 * These guards pin the mechanism, not just the instances: the shared pump must
 * bound the *wait* without cancelling or interrupting the work, and no test may
 * wrap a DataStore write in a cancelling timeout. Pure JVM: reads the sources.
 */
class DataStoreWriteCancellationGuardTest {

    private fun source(name: String): String =
        stripComments(File("src/test/java/com/mcqapp/$name").let {
            assertTrue("missing: ${it.absolutePath}", it.isFile)
            it.readText()
        })

    private val testFiles: List<File> =
        File("src/test/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // This guard's own string literals name the banned tokens.
            .filter { it.name != "DataStoreWriteCancellationGuardTest.kt" }
            .toList()

    /**
     * Comments are stripped so a KDoc that *explains* the rule ("no
     * withTimeout around an edit") cannot trip a guard that *enforces* it.
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder()
        var blockDepth = 0
        var line = StringBuilder()
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            if (blockDepth > 0) {
                when {
                    c == '*' && next == '/' -> { blockDepth--; i++ }
                }
            } else when {
                inString -> {
                    line.append(c)
                    if (c == '"') inString = false
                }
                c == '"' -> { inString = true; line.append(c) }
                c == '/' && next == '*' -> { blockDepth++; i++ }
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                    continue
                }
                else -> line.append(c)
            }
            if (i + 1 < text.length && text[i + 1] == '\n' && blockDepth > 0) line.append('\n')
            if (c == '\n' && blockDepth == 0) { out.append(line); line = StringBuilder() }
            i++
        }
        out.append(line)
        return out.toString()
    }

    /** Every rule the pump must satisfy; empty means the pump is safe. */
    private fun pumpOffences(pump: String): List<String> = buildList {
        if (pump.contains("withTimeout(")) add(
            "runOffMainWhilePumping must not wrap the block in a cancelling " +
                "timeout: a DataStore edit cancelled mid-flight poisons the " +
                "process-wide store for every later test"
        )
        if (Regex("""\.interrupt\(\)""").containsMatchIn(pump)) add(
            "runOffMainWhilePumping must not interrupt its worker at the " +
                "deadline: an interrupt cancels runBlocking, which is the same " +
                "mid-flight cancellation"
        )
        if (!pump.contains("runBlocking { block() }")) add(
            "runOffMainWhilePumping must run the block as plain runBlocking"
        )
    }

    @Test
    fun theSharedPumpBoundsTheWaitWithoutCancellingOrInterruptingTheWork() {
        assertTrue(
            pumpOffences(source("RobolectricMainThread.kt")).joinToString("\n"),
            pumpOffences(source("RobolectricMainThread.kt")).isEmpty()
        )
    }

    /** Negative case: re-introduce the cancelling pump and the guard must fire. */
    @Test
    fun thePumpGuardDetectsTheCancellingPumpItRemoved() {
        val regressed = source("RobolectricMainThread.kt")
            .replace("runBlocking { block() }", "withTimeout(timeoutMs) { block() }")
        assertTrue(
            "the mutation must reintroduce the cancelling pump",
            regressed.contains("withTimeout(timeoutMs) { block() }")
        )
        assertTrue(
            "the guard must reject a pump that cancels its block",
            pumpOffences(regressed).isNotEmpty()
        )
    }

    @Test
    fun theSettingsResetRuleNeverCancelsItsEdit() {
        val rule = source("SettingsResetRule.kt")
        val at = rule.indexOf("suspend fun clearGradingKeysSuspend")
        assertTrue("clearGradingKeysSuspend not found in SettingsResetRule.kt", at >= 0)
        assertFalse(
            "the grading-key clear must not be cancellable",
            balancedBlock(rule, at).contains("withTimeout(")
        )
    }

    /**
     * No test may bound a DataStore-writing call with a cancelling timeout. The
     * writes this suite performs: the process-wide key clear, a whole-file
     * clear, a settings restore, and the config read that seeds on a fresh
     * install (its seed is a write). Reads (`data.first()`) stay allowed.
     */
    @Test
    fun noTestCancelsADataStoreWrite() {
        val banned = listOf(
            "dataStore.edit",
            "clearGradingKeysSuspend",
            "settingsRestore(",
            "schedulerConfigNow()"
        )
        val offenders = mutableListOf<String>()
        for (file in testFiles) {
            val code = stripComments(file.readText())
            var at = code.indexOf("withTimeout")
            while (at >= 0) {
                if (banned.any { balancedBlock(code, at).contains(it) }) offenders += file.name
                at = code.indexOf("withTimeout", at + 1)
            }
        }
        assertTrue(
            "these tests wrap a DataStore write in a cancelling withTimeout: " +
                offenders.joinToString(),
            offenders.isEmpty()
        )
    }

    /** The text of the balanced `{ ... }` block opened on or after [from]. */
    private fun balancedBlock(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        if (open < 0) return ""
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        return text.substring(open)
    }
}
