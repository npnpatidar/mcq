package com.mcqapp

import android.app.Application
import android.os.Process
import android.util.Log
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.util.Logger

class McqApplication : Application() {

    val repository: McqRepository by lazy {
        McqRepository(AppDatabase.get(this), this)
    }

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        Logger.i("APP", "Application onCreate (pid=${Process.myPid()})")

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Logger.crash(throwable)
            Logger.e("APP", "Uncaught exception on thread '${thread.name}', pid=${Process.myPid()}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
                ?: Log.e("APP", "No previous uncaught exception handler", throwable)
        }
    }
}
