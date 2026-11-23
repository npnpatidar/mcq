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

    /**
     * A warning a fallback hit — report it once per distinct `(tag, message)`
     * instead of once per row.
     *
     * The error ring only records `E`/`CRASH`, so a swallowed parse failure or
     * an unavailable XXE hardening feature used to leave no diagnostic at all:
     * the app degraded quietly, and the first clue was a user complaining that
     * questions went missing. This writes the normal `Logger.w` line *and*
     * records the same message in [recentErrors], so a JVM test can assert the
     * warning happened without a logcat or a log file. Repeated hits with the
     * same tag and message are suppressed — a 100k-row import must not allocate
     * 100k ring entries or log lines for one repeated condition.
     */
    fun warnOnce(tag: String, message: String, throwable: Throwable? = null) {
        val key = "$tag\u0000$message"
        if (!warnOnceKeys.add(key)) return
        warnOnceOrder.add(key)
        // Bounded: a reader that quotes the file or a per-row message can mint
        // unbounded distinct keys over a long session. Oldest keys are evicted,
        // so a genuinely rare warning can be reported again later.
        while (warnOnceKeys.size > MAX_WARN_ONCE_KEYS) {
            warnOnceOrder.poll()?.let { warnOnceKeys.remove(it) }
        }
        w(tag, message, throwable)
        recordError("W", tag, message)
    }

    /** Test seam: forget which warnings have already been reported. */
    internal fun resetWarnOnce() {
        warnOnceKeys.clear()
        warnOnceOrder.clear()
    }

    /** Visible for the bound test: how many dedup keys are retained. */
    internal fun warnOnceKeyCount(): Int = warnOnceKeys.size

    private val warnOnceKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val warnOnceOrder = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private const val MAX_WARN_ONCE_KEYS = 1024

    fun crash(throwable: Throwable) {
        val message = throwable.javaClass.name + ": " + throwable.message
        val line = formatLine("CRASH", "UNCAUGHT", message, throwable)
        publish("CRASH", "UNCAUGHT", message)
        // The crash line is written to the file SYNCHRONOUSLY. The uncaught
        // handler calls this and then hands off to the previous handler,
        // which kills the process — a line queued on the log executor
        // routinely never ran, so the one report that matters most was the
        // one most likely to be lost.
        try {
            appendToFile(line)
        } catch (e: Throwable) {
            // A disk failure must never escape the handler: the crash
            // itself is what is being reported.
            try {
                Log.w(TAG, "Failed to write the crash log: ${e.message}")
            } catch (_: Throwable) {
                // Nothing left to do.
            }
        }
    }

    /**
     * A bounded ring of the most recent error/crash lines, for tests: a
     * swallowed-and-logged exception is otherwise invisible to any assertion
     * (the every-startup "getStudyCounts failed" NPE shipped silently for
     * days because nothing could observe it).
     */
    private val recentErrors = ConcurrentLinkedDeque<String>()

    fun recentErrors(limit: Int = 20): List<String> = recentErrors.toList().takeLast(limit)

    fun clearRecentErrors() {
        recentErrors.clear()
        // A test that clears the ring is starting a clean observation window;
        // keeping the old dedup keys would hide the first warning it expects.
        resetWarnOnce()
    }

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

    /** Adds one bounded line to the in-memory ring used by tests. */
    private fun recordError(level: String, tag: String, message: String) {
        recentErrors.addLast("$level/$tag: $message")
        while (recentErrors.size > 100) recentErrors.removeFirst()
    }

    /** logcat and the in-memory ring of recent errors. */
    private fun publish(level: String, tag: String, message: String) {
        if (level == "E" || level == "CRASH") {
            recordError(level, tag, message)
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
