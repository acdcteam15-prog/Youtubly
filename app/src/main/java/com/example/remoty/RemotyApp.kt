package com.example.remoty

import android.app.Application
import java.io.File
import java.util.Date

class RemotyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        // Keep the last crash (with the app log) so the Diagnostics button can show it after the restart.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "last_crash.txt").writeText(
                    "${Date()}\nthread ${t.name}\n${e.stackTraceToString()}\n\napp log:\n${AppLog.dump()}\n"
                )
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
        AppLog.i("app started ${BuildConfig.VERSION_LABEL}")
    }
}
