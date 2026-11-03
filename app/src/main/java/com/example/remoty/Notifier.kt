package com.example.remoty

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Notifier {
    const val CH_SERVICE = "connection"
    const val CH_JOBS = "jobs"
    const val CH_PROGRESS = "progress"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_JOBS, "Build results", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Live progress", NotificationManager.IMPORTANCE_LOW))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun connection(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_remoty).setContentTitle("Remoty").setContentText(text)
            .setOngoing(true).setContentIntent(openApp(ctx)).build()

    fun job(ctx: Context, id: String, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, CH_JOBS)
            .setSmallIcon(R.drawable.ic_stat_remoty).setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(openApp(ctx)).build()
        ctx.getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)
    }

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun progressId(jobId: String) = 7000 + (jobId.hashCode() and 0xFFFF)

    /** The ongoing "build running" notification: title, stage, the last line of output, and a Stop button. */
    fun progress(ctx: Context, j: JobSummary) {
        if (!allowed(ctx)) return
        val stop = PendingIntent.getBroadcast(
            ctx, progressId(j.id), Intent(ctx, NotifActionReceiver::class.java).setAction("stop").putExtra("job", j.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (j.waitingInput) "Waiting for your answer: ${j.lastLine}" else j.lastLine.ifBlank { j.phase }
        val n = NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_remoty).setContentTitle(j.title).setContentText(text).setSubText(j.phase)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true).setContentIntent(openApp(ctx))
            .addAction(0, "Stop", stop).build()
        ctx.getSystemService(NotificationManager::class.java).notify(progressId(j.id), n)
    }

    fun clearProgress(ctx: Context, jobId: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(progressId(jobId))
    }

    /** "APK ready" with an Install button that opens the installer through the app. */
    fun installReady(ctx: Context, j: JobSummary) {
        if (!allowed(ctx)) return
        val install = PendingIntent.getActivity(
            ctx, j.id.hashCode(), Intent(ctx, MainActivity::class.java).putExtra("install_job", j.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CH_JOBS)
            .setSmallIcon(R.drawable.ic_stat_remoty).setContentTitle("APK ready: ${j.title}")
            .setContentText(j.artifactName ?: "Tap Install").setAutoCancel(true).setContentIntent(install)
            .addAction(0, "Install", install).build()
        ctx.getSystemService(NotificationManager::class.java).notify(j.id.hashCode(), n)
    }
}
