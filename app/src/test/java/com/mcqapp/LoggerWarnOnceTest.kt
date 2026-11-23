package com.mcqapp

import com.mcqapp.util.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Pure-JVM tests for [Logger.warnOnce], the diagnostic channel used by
 * non-fatal fallbacks (DOCX hardening/parse degradation, and the Study
 * stale-config fallback). A swallowed failure must be visible in BOTH the
 * normal warning stream and the error ring, exactly once per distinct message.
 */
class LoggerWarnOnceTest {

    private fun stdoutDuring(block: () -> Unit): String {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        System.setOut(PrintStream(bytes, true, Charsets.UTF_8.name()))
        try {
            block()
        } finally {
            System.setOut(original)
            System.out.flush()
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun setUp() {
        Logger.clearRecentErrors()
        Logger.resetWarnOnce()
    }

    @Test
    fun aWarningFeedsBothTheWarningStreamAndTheErrorRing() {
        setUp()
        // On a plain JVM android.util.Log is a throwing stub, so Logger's
        // fallback prints the line to stdout — that is the observable proof
        // that Logger.w ran, not just that the ring was appended to.
        val out = stdoutDuring {
            Logger.warnOnce("DOCX", "hardening feature unavailable", IllegalStateException("boom"))
        }
        assertTrue("Logger.w must emit the line: $out", "W/DOCX: hardening feature unavailable" in out)
        assertTrue(
            "the warning must also reach recentErrors: ${Logger.recentErrors()}",
            Logger.recentErrors().any { it == "W/DOCX: hardening feature unavailable" }
        )
    }

    @Test
    fun repeatedWarningsAreSuppressedButDistinctOnesAreNot() {
        setUp()
        val out = stdoutDuring {
            repeat(5) { Logger.warnOnce("DOCX", "payload stale") }
            Logger.warnOnce("DOCX", "a different fallback")
            Logger.warnOnce("STUDY", "payload stale")
        }
        assertEquals(1, countOccurrences(out, "W/DOCX: payload stale"))
        assertEquals(1, countOccurrences(out, "W/DOCX: a different fallback"))
        assertEquals(1, countOccurrences(out, "W/STUDY: payload stale"))
        assertEquals(3, Logger.recentErrors().size)
    }

    @Test
    fun clearingTheRingAlsoForgetsWhichWarningsWereReported() {
        setUp()
        stdoutDuring { Logger.warnOnce("DOCX", "parse degraded") }
        assertEquals(1, Logger.recentErrors().size)
        Logger.clearRecentErrors()
        val out = stdoutDuring { Logger.warnOnce("DOCX", "parse degraded") }
        assertEquals(1, countOccurrences(out, "W/DOCX: parse degraded"))
        assertEquals(1, Logger.recentErrors().size)
    }

    @Test
    fun anOrdinaryWarningDoesNotPolluteTheErrorRing() {
        setUp()
        stdoutDuring { Logger.w("DOCX", "a routine warning") }
        assertFalse(
            "only warnOnce promotes a warning into the ring: ${Logger.recentErrors()}",
            Logger.recentErrors().any { "routine warning" in it }
        )
    }

    @Test
    fun ordinaryErrorsStillReachTheRingAfterTheRefactor() {
        setUp()
        stdoutDuring { Logger.e("REPO", "real failure") }
        assertTrue(
            "the E-channel guard must stay green: ${Logger.recentErrors()}",
            Logger.recentErrors().any { it == "E/REPO: real failure" }
        )
    }

    private fun countOccurrences(haystack: String, needle: String): Int =
        if (needle.isEmpty()) 0 else haystack.windowed(needle.length).count { it == needle }
}
