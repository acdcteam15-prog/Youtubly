package com.youtubly.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.youtubly.app.MainActivity
import com.youtubly.app.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Downloads an app update as foreground work (with a progress notification), so the screen turning off or the app
 * being left does not stop it. It continues a partial file after a dropped connection (see UpdateRepository.download).
 */
@HiltWorker
class UpdateDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: UpdateRepository
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(-1)

    override suspend fun doWork(): Result {
        val build = inputData.getInt(K_BUILD, -1)
        val url = inputData.getString(K_URL)
        if (build < 0 || url == null) return Result.failure()
        val info = ReleaseInfo(
            versionName = inputData.getString(K_VERSION).orEmpty(), build = build, notes = "",
            required = inputData.getBoolean(K_REQUIRED, false), apkUrl = url, apkApiUrl = inputData.getString(K_API),
            sizeBytes = inputData.getLong(K_SIZE, -1L), sha256Url = inputData.getString(K_SHA),
            sha256ApiUrl = inputData.getString(K_SHA_API), publishedAt = null
        )
        runCatching { setForeground(foreground(-1)) }

        val finished = coroutineScope {
            // Mirror the progress into the notification (about once a second).
            val watcher = launch {
                var lastAt = 0L
                var lastPct = -2
                repository.state.collect { st ->
                    if (st is UpdateState.Downloading) {
                        val pct = if (st.fraction >= 0f) (st.fraction * 100).toInt() else -1
                        val now = System.currentTimeMillis()
                        if (pct != lastPct && now - lastAt > 1_000) {
                            lastAt = now; lastPct = pct
                            runCatching { setForeground(foreground(pct)) }
                        }
                    }
                }
            }
            val result = repository.download(info)
            watcher.cancel()
            result
        }
        return if (finished is UpdateState.Ready) { notifyReady(info); Result.success() } else Result.failure()
    }

    private fun foreground(percent: Int): ForegroundInfo {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(DOWNLOAD_CHANNEL, "Update download", NotificationManager.IMPORTANCE_LOW))
        val builder = NotificationCompat.Builder(appContext, DOWNLOAD_CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_play)
            .setContentTitle("Downloading the Youtubly update")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (percent in 0..100) builder.setProgress(100, percent, false).setContentText("$percent%")
        else builder.setProgress(0, 0, true)
        return ForegroundInfo(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyReady(info: ReleaseInfo) {
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted || !NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(READY_CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("youtubly://updates"))
            .setClass(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(appContext, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(
            READY_ID,
            NotificationCompat.Builder(appContext, READY_CHANNEL)
                .setSmallIcon(R.drawable.ic_tile_play)
                .setContentTitle("Youtubly ${info.versionName} is ready to install")
                .setContentText("Tap to install it now")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val DOWNLOAD_CHANNEL = "app_update_download"
        private const val READY_CHANNEL = "app_updates"
        private const val NOTIFICATION_ID = 7002
        private const val READY_ID = 7003
        private const val WORK_NAME = "update-download"
        private const val K_VERSION = "version"
        private const val K_BUILD = "build"
        private const val K_URL = "url"
        private const val K_API = "api"
        private const val K_SIZE = "size"
        private const val K_SHA = "sha"
        private const val K_SHA_API = "shaApi"
        private const val K_REQUIRED = "required"

        fun enqueue(context: Context, info: ReleaseInfo) {
            val data = workDataOf(
                K_VERSION to info.versionName, K_BUILD to info.build, K_URL to info.apkUrl, K_API to info.apkApiUrl,
                K_SIZE to info.sizeBytes, K_SHA to info.sha256Url, K_SHA_API to info.sha256ApiUrl, K_REQUIRED to info.required
            )
            val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // A download that is already running keeps going; tapping again does not start a second one.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
