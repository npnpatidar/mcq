package com.mcqapp

import android.app.Application
import android.os.Process
import android.util.Log
import com.mcqapp.data.anki.AnkiTempFiles
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class McqApplication : Application() {

    val repository: McqRepository by lazy {
        McqRepository(AppDatabase.get(this), this)
    }

    /**
     * Process-scoped work that must finish eventually but must never block
     * startup. The FSRS-7 migration and card rebuild live here (AGENTS.md: no
     * WorkManager dependency); both are idempotent and resumable, so a killed
     * process simply resumes next launch.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        AnkiTempFiles.cleanup(this)
        Logger.i("APP", "Application onCreate (pid=${Process.myPid()})")

        applicationScope.launch {
            try {
                repository.ensureFsrs7()
            } catch (e: Exception) {
                Logger.e("FSRS7", "model migration failed", e)
            }
            try {
                repository.rebuildFsrs7Memory()
            } catch (e: Exception) {
                Logger.e("FSRS7", "card-state rebuild failed; will resume next launch", e)
            }
        }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Logger.crash(throwable)
            Logger.e("APP", "Uncaught exception on thread '${thread.name}', pid=${Process.myPid()}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
                ?: Log.e("APP", "No previous uncaught exception handler", throwable)
        }
    }
}
