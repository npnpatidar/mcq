package com.mcqapp

import androidx.test.core.app.ApplicationProvider
import com.mcqapp.util.Logger
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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
        // A directory at the target path makes renameTo fail.
        File(dir, "app.log.1").mkdirs()
        Logger.rotate(file, 10)
        assertTrue("failed rename leaves the target alone", File(dir, "app.log.1").isDirectory)
        assertTrue("live file truncated", file.readText().startsWith("=== Log truncated"))
    }

    private fun tempDir(): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return File(context.cacheDir, "logger-test-${System.nanoTime()}").apply { mkdirs() }
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
