package com.youtubly.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

/** Why the last install did not go through (null = nothing wrong). Shown on the update screen. */
object UpdateInstallResult {
    val failure = MutableStateFlow<String?>(null)
}

/** Receives Android's answer to an install session: asks the user to confirm when needed, reports failures. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateInstallResult.failure.value = null
            else -> UpdateInstallResult.failure.value =
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() } ?: "Android could not install the update."
        }
    }

    companion object { const val ACTION = "com.youtubly.app.UPDATE_INSTALL_RESULT" }
}
