package com.example.remoty

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Handles the Stop button on the live-progress notification. */
class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("job") ?: return
        if (intent.action != "stop") return
        val api = Session.api ?: return
        val pending = goAsync()
        thread {
            try { api.stop(id); AppLog.i("stopped job $id from the notification") } catch (_: Exception) {}
            pending.finish()
        }
    }
}
