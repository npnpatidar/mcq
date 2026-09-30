package com.mcqapp.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    private const val TAG = "MCQApp"
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    private var logFile: File? = null
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * File IO runs on this single-thread executor so callers (including Main)
     * never block on disk; the daemon thread never keeps the process alive.
     * One thread keeps log lines in write order.
     */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mcq-log").apply { isDaemon = true }
    }

    var activePath: String = "not initialized"
        private set

    fun init(context: Context) {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), "logs"),
            File(context.filesDir, "logs")
        )
        for (dir in candidates) {
            try {
                dir.mkdirs()
                if (!dir.canWrite()) continue
                val file = File(dir, "app.log")
                file.appendText(
                    "=== Log session started ${timeFormat.format(Date())} " +
                        "(pid=${android.os.Process.myPid()}) ===\n"
                )
                synchronized(lock) {
                    logFile = file
                    activePath = file.absolutePath
                }
                Log.i(TAG, "Logging to ${file.absolutePath}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Cannot use log dir ${dir.absolutePath}: ${e.message}")
            }
        }
        Log.e(TAG, "No writable log directory found")
    }

    fun logFilePath(): String? = synchronized(lock) { logFile?.absolutePath }

    fun d(tag: String, message: String) = write("D", tag, message, null)

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) = write("W", tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = write("E", tag, message, throwable)

    fun crash(throwable: Throwable) = write("CRASH", "UNCAUGHT", throwable.javaClass.name + ": " + throwable.message, throwable)

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val trace = if (throwable != null) {
            try {
                Log.getStackTraceString(throwable)
            } catch (_: Exception) {
                throwable.stackTraceToString()
            }
        } else {
            null
        }
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ').append(level).append('/').append(tag)
            append(" [${Thread.currentThread().name}]: ")
            append(message)
            if (trace != null) {
                append('\n').append(trace)
            }
            append('\n')
        }
        // Logging must never crash its caller, on device or on plain-JVM tests.
        try {
            Log.println(
                when (level) {
                    "D" -> Log.DEBUG
                    "I" -> Log.INFO
                    "W" -> Log.WARN
                    else -> Log.ERROR
                },
                tag,
                message
            )
        } catch (_: Exception) {
            println("$level/$tag: $message")
        }
        // logcat stays synchronous (cheap); file IO moves off the caller.
        try {
            io.execute {
                try {
                    val file = synchronized(lock) { logFile } ?: return@execute
                    if (file.length() > MAX_FILE_BYTES) {
                        rotate(file)
                    }
                    file.appendText(line)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write log: ${e.message}")
                }
            }
        } catch (_: Exception) {
            // Executor rejected or shut down: drop the line, never crash.
        }
    }

    private fun rotate(file: File) = rotate(file, MAX_FILE_BYTES)

    /**
     * Bounded rotation: rename to `app.log.1`, or truncate if the rename
     * fails, so the live file never grows without limit.
     */
    internal fun rotate(file: File, maxBytes: Long) {
        val rotated = File(file.parentFile, "app.log.1")
        // A stale target can make renameTo fail; clear it first.
        rotated.delete()
        if (file.renameTo(rotated)) {
            file.appendText("=== Log rotated ${timeFormat.format(Date())} ===\n")
        } else {
            Log.w(TAG, "Log rotation failed; truncating ${file.absolutePath}")
            file.writeText("=== Log truncated ${timeFormat.format(Date())} ===\n")
        }
    }
}
