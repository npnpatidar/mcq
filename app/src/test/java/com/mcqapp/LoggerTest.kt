package com.mcqapp

import androidx.test.core.app.ApplicationProvider
import com.mcqapp.util.Logger
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/**
 * File logging is asynchronous, so delivery is polled; rotation is exercised
 * directly with a tiny threshold instead of writing 8 MB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoggerTest {

    @Before
    fun setup() {
        Logger.init(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        // The crash test swaps the file-write executor for one that drops
        // every task; the shipped one goes back whatever the test did.
        Logger.resetIoExecutor()
    }

    @Test
    fun logLinesReachTheFileOffTheCallerThread() {
        Logger.i("TEST", "hello-async")
        val file = File(Logger.logFilePath()!!)
        assertTrue("log line never reached ${file.absolutePath}", awaitFileContains(file, "hello-async"))
    }

    @Test
    fun rotationRenamesTheOldFile() {
        val dir = tempDir()
        val file = File(dir, "app.log")
        file.writeText("x".repeat(100))
        Logger.rotate(file, 10)
        assertTrue("old file renamed", File(dir, "app.log.1").exists())
        assertTrue("live file holds the rotated header", file.readText().startsWith("=== Log rotated"))
    }

    @Test
    fun rotationFailureTruncatesInsteadOfGrowing() {
        val dir = tempDir()
        val file = File(dir, "app.log")
        file.writeText("x".repeat(100))
        // A non-empty directory at the target path: delete() cannot remove it
        // and renameTo fails, forcing the truncate fallback.
        File(dir, "app.log.1").mkdirs()
        File(dir, "app.log.1" + File.separator + "keep").writeText("locked")
        Logger.rotate(file, 10)
        assertTrue("failed rename leaves the target alone", File(dir, "app.log.1").isDirectory)
        assertTrue("live file truncated", file.readText().startsWith("=== Log truncated"))
    }

    private fun tempDir(): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return File(context.cacheDir, "logger-test-${System.nanoTime()}").apply { mkdirs() }
    }

    /**
     * A crash report is the one line that must not be lost: the uncaught
     * handler writes it and then hands off to the previous handler, which
     * kills the process, so a line queued on the log executor routinely
     * never ran.
     *
     * The executor is replaced with one that drops every task, so the
     * asynchronous path cannot write anything: whatever the file holds was
     * written synchronously. No sleeping and no polling — the line is in
     * the file when crash() returns.
     */
    @Test
    fun theCrashLineIsInTheFileWhenTheCallReturns() {
        Logger.io = Executor { }
        Logger.init(ApplicationProvider.getApplicationContext())
        Logger.crash(IllegalStateException("the sky is falling"))
        val file = File(Logger.logFilePath()!!)
        val text = file.readText()
        assertTrue("the crash line must be in the file: $text", text.contains("CRASH/UNCAUGHT"))
        assertTrue("the message must be in the file: $text", text.contains("the sky is falling"))
        assertTrue(
            "the stack must be in the file: $text",
            text.contains("at com.mcqapp.LoggerTest")
        )
    }

    private fun awaitFileContains(file: File, needle: String): Boolean {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.readText().contains(needle)) return true
            Thread.sleep(20)
        }
        return false
    }
}
