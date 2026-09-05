package com.birdmachine.paidin

import android.app.Application
import android.util.Log

class PaidInApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                getSharedPreferences("paidin", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", Log.getStackTraceString(throwable))
                    .putLong("last_crash_at", System.currentTimeMillis())
                    .commit()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
