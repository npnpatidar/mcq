package com.mcqapp

import android.app.Application
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.mcqapp.data.anki.AnkiTempFiles
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class McqApplication : Application() {

    /**
     * Startup jobs must never take the process down. Each one already catches
     * its own failure, but this handler is the backstop for a bug in that
     * guard: a fatal exception there must not become an unrecoverable launch
     * loop.
     */
    private val startupExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.e("APP", "Startup job failed", throwable)
    }

    private val startupScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + startupExceptionHandler)

    /**
     * Whether `onCreate` launches the first-start grading-rule decision.
     * Production does: the answer must be taken before the learner can import
     * a paper in this run. Tests that pin their own preference fixtures opt
     * out, because the probe opens the real database and writes a
     * process-wide marker that would race their DataStore setup.
     */
    @VisibleForTesting
    internal open val seedsInstallFreshnessAtStartup: Boolean = true

    /** The production wiring: a new install is one whose database is empty. */
    val repository: McqRepository by lazy {
        McqRepository(AppDatabase.get(this), this)
    }

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        AnkiTempFiles.cleanup(this)
        Logger.i("APP", "Application onCreate (pid=${Process.myPid()})")

        // Install the handler before anything is launched: a fast failure in
        // the startup job must still be logged, and a crash while handling it
        // must not recurse into an unset handler.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Logger.crash(throwable)
            Logger.e("APP", "Uncaught exception on thread '${thread.name}', pid=${Process.myPid()}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
                ?: Log.e("APP", "No previous uncaught exception handler", throwable)
        }

        // Decide the first-start grading rule now, on IO. The answer comes
        // from database content, so it must be taken before the learner can
        // import a paper in this run — but it must not block onCreate or open
        // the database on the main thread. Every later config read awaits the
        // same decision, and a failure here must not crash the launch: the
        // probe already answers "not new" and the job catches anything else.
        if (seedsInstallFreshnessAtStartup) {
            startupScope.launch {
                runCatching { repository.decideInstallFreshness() }
                    .onFailure { Logger.e("APP", "Could not decide install freshness", it) }
            }
        }
    }
}
