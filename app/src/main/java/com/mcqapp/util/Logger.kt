package com.mcqapp.util

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object Logger {

    private const val TAG = "MCQApp"
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    private var logFile: File? = null
    private val lock = Any()

    /**
     * The timestamp format, thread-safe by construction. A shared
     * `SimpleDateFormat` is not: it is used from the callers' threads and
     * from the log thread, and a `DateTimeFormatter` is immutable.
     */
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** The same stamp `SimpleDateFormat` produced: the default zone, to the millisecond. */
    private fun timestamp(): String = timeFormat.format(LocalDateTime.now())

    /**
     * File IO runs on this single-thread executor so callers (including Main)
     * never block on disk; the daemon thread never keeps the process alive.
     * One thread keeps log lines in write order.
     */
    private val defaultIo: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mcq-log").apply { isDaemon = true }
    }

    /**
     * The executor the file writes are queued on. A seam for the tests: an
     * executor that drops every task must not be able to swallow a crash
     * line — which is written synchronously anyway, and that is the point.
     */
    @Volatile
    internal var io: Executor = defaultIo

    /** Puts the file-write executor back to the shipped one. */
    internal fun resetIoExecutor() {
        io = defaultIo
    }

    var activePath: String = "not initialized"
        private set

    /**
     * Per-item tracing for bulk work (import loops over 100k questions,
     * batched saves). Off by default: string building and file IO inside a
     * hot loop cost O(items), and the batch summary lines already tell the
     * whole story. Callers inside per-item loops read this flag; one-off
     * logs never check it.
     */
    var verbose: Boolean = false

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
                    "=== Log session started ${timestamp()} " +
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

    // AUDIT MUTANT: the crash line is queued like every other line again.
    fun crash(throwable: Throwable) = write(
        "CRASH",
        "UNCAUGHT",
        throwable.javaClass.name + ": " + throwable.message,
        throwable
    )

    /**
     * A bounded ring of the most recent error/crash lines, for tests: a
     * swallowed-and-logged exception is otherwise invisible to any assertion
     * (the every-startup "getStudyCounts failed" NPE shipped silently for
     * days because nothing could observe it).
     */
    private val recentErrors = ConcurrentLinkedDeque<String>()

    fun recentErrors(limit: Int = 20): List<String> = recentErrors.toList().takeLast(limit)

    fun clearRecentErrors() = recentErrors.clear()

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val line = formatLine(level, tag, message, throwable)
        publish(level, tag, message)
        // logcat stays synchronous (cheap); file IO moves off the caller.
        try {
            io.execute {
                try {
                    appendToFile(line)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write log: ${e.message}")
                }
            }
        } catch (_: Exception) {
            // Executor rejected or shut down: drop the line, never crash.
        }
    }

    /** One log line, as it is written to the file. */
    private fun formatLine(level: String, tag: String, message: String, throwable: Throwable?): String {
        val trace = if (throwable != null) {
            try {
                Log.getStackTraceString(throwable)
            } catch (_: Exception) {
                throwable.stackTraceToString()
            }
        } else {
            null
        }
        return buildString {
            append(timestamp())
            append(' ').append(level).append('/').append(tag)
            append(" [${Thread.currentThread().name}]: ")
            append(message)
            if (trace != null) {
                append('\n').append(trace)
            }
            append('\n')
        }
    }

    /** logcat and the in-memory ring of recent errors. */
    private fun publish(level: String, tag: String, message: String) {
        if (level == "E" || level == "CRASH") {
            recentErrors.addLast("$level/$tag: $message")
            while (recentErrors.size > 100) recentErrors.removeFirst()
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
    }

    /**
     * Appends one line to the log file, rotating it first if it has grown
     * past the limit. The rotation check and the append share the lock, so
     * two writers cannot both decide to rotate — and the crash line, which
     * is written on the crashing thread, gets the same treatment as every
     * other line.
     */
    private fun appendToFile(line: String) {
        synchronized(lock) {
            val file = logFile ?: return
            if (file.length() > MAX_FILE_BYTES) {
                rotate(file)
            }
            file.appendText(line)
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
            file.appendText("=== Log rotated ${timestamp()} ===\n")
        } else {
            Log.w(TAG, "Log rotation failed; truncating ${file.absolutePath}")
            file.writeText("=== Log truncated ${timestamp()} ===\n")
        }
    }
}
