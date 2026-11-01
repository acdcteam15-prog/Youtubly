package com.youtubly.app.editor

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.youtubly.app.core.Constants
import com.youtubly.app.data.local.dao.EditJobDao
import com.youtubly.app.data.local.entity.EditJobEntity
import com.youtubly.app.data.repository.EditRepository
import com.youtubly.app.domain.editor.EditJobStatus
import com.youtubly.app.domain.editor.EditorFormat
import com.youtubly.app.domain.editor.ExportMath
import com.youtubly.app.domain.editor.OutputMath
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

/**
 * Drains the edit queue: takes the oldest QUEUED job, exports it, saves it to Downloads,
 * repeats until nothing is queued. Always returns success so the next drain in the unique
 * chain still runs (a failed export is recorded on its row, not on the WorkManager chain).
 *
 * Survives the app being closed: WorkManager keeps the work, and a job interrupted by the
 * process dying is put back in the queue by [EditJobDao.requeueInterrupted] on the next run.
 */
@HiltWorker
class EditWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: EditJobDao,
    private val exporter: VideoExporter,
    private val saver: MediaStoreSaver
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing video edit", 0)

    override suspend fun doWork(): Result {
        dao.requeueInterrupted()
        while (!isStopped) {
            val job = dao.nextQueued() ?: break
            if (dao.claim(job.id) == 0) continue // cancelled between the read and the claim
            process(job)
        }
        return Result.success()
    }

    private suspend fun process(job: EditJobEntity) {
        val temp = File(applicationContext.cacheDir, "editor_export/${job.id}.mp4")
        try {
            val spec = EditRepository.specOf(job) ?: throw ExportFailed("This export's settings couldn't be read")
            if (!spec.isValid) throw ExportFailed("Nothing selected to export")
            val inputs = spec.clips.map { File(it.path) }
            if (inputs.any { !it.isFile }) {
                throw ExportFailed(if (inputs.size == 1) "The saved video no longer exists" else "One of the videos no longer exists")
            }

            val needed = OutputMath.estimateBytes(spec, inputs.map { it.length() })
            val usable = applicationContext.cacheDir.usableSpace
            if (!ExportMath.hasRoomFor(needed, usable)) throw ExportFailed("Not enough free storage for this export")

            updateForeground(job.title, 0)
            var lastShown = 0
            exporter.export(
                spec = spec,
                output = temp,
                onProgress = { p ->
                    dao.updateProgress(job.id, p)
                    if (p - lastShown >= 2) { lastShown = p; updateForeground(job.title, p) }
                },
                shouldCancel = { dao.statusOf(job.id) == EditJobStatus.CANCELLED.name }
            )
            if (!temp.isFile || temp.length() == 0L) throw ExportFailed("The export produced no file")

            val wantedName = EditorFormat.outputFileName(job.title, LocalDateTime.now(), spec.fileExtension)
            val uri = saver.saveExport(temp, wantedName, spec.mimeType)
            val finalName = saver.displayName(uri, wantedName)
            val stillRunning = dao.markCompleted(job.id, uri.toString(), finalName, System.currentTimeMillis()) > 0
            if (stillRunning) {
                notifyFinished(job, finalName)
            } else {
                // Cancelled while saving: honour the cancel, don't leave a stray file.
                saver.delete(uri)
            }
        } catch (cancelled: ExportCancelledByUser) {
            // Row is already CANCELLED; nothing to record.
        } catch (c: CancellationException) {
            // The worker itself was stopped (system constraints, quota, app update…).
            // Put the job back so the rescheduled work picks it up again.
            withContext(NonCancellable) { dao.requeue(job.id) }
            throw c
        } catch (t: Throwable) {
            val message = (t as? ExportFailed)?.message ?: t.message?.takeIf { it.isNotBlank() } ?: "Export failed"
            dao.markFailed(job.id, message, System.currentTimeMillis())
        } finally {
            runCatching { temp.delete() }
        }
    }

    private suspend fun updateForeground(title: String, progress: Int) {
        // Can fail when the app is in the background on Android 12+ (FGS start limits); the
        // export still runs, it's just not promoted to a foreground service.
        runCatching { setForeground(foregroundInfo(title, progress)) }
    }

    private fun foregroundInfo(title: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Constants.EDITOR_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Editing: $title")
            .setContentText(if (progress > 0) "$progress%" else "Starting…")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .build()
        return ForegroundInfo(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyFinished(job: EditJobEntity, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(applicationContext, Constants.EDITOR_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Edit saved")
            .setContentText("Download/${Constants.EDITOR_OUTPUT_RELATIVE_DIR}/$name")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(job.id.hashCode(), notification) }
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName) ?: return null
        return PendingIntent.getActivity(applicationContext, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val UNIQUE_NAME = "youtubly_edit_queue"
        const val TAG = "youtubly_edit"
        private const val FOREGROUND_ID = 0x0ED17
    }
}
