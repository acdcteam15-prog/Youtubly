package com.example.remoty

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/** Foreground service: keeps the process (and the embedded ZeroTier node) alive while you are away from the app. */
class RemotyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "Connected to your laptop"
        val n = Notifier.connection(this, text)
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context, text: String) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, RemotyService::class.java).putExtra("text", text))

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, RemotyService::class.java)) }
    }
}
