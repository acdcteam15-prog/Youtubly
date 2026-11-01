package com.youtubly.app.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.youtubly.app.core.Constants
import com.youtubly.app.data.local.dao.DownloadDao
import com.youtubly.app.domain.model.DownloadStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val userPreferences: com.youtubly.app.data.local.datastore.UserPreferences
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(inputData.getString(KEY_TITLE) ?: "Downloading", 0)

    private fun foregroundInfo(title: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, Constants.DOWNLOAD_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return@withContext Result.failure()
        val streamUrl = inputData.getString(KEY_STREAM_URL) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "download"
        val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, false)
        val extension = inputData.getString(KEY_EXTENSION) ?: if (audioOnly) "m4a" else "mp4"
        // A retry (low battery pause) must continue the partial file, not start over.
        val resume = inputData.getBoolean(KEY_RESUME, false) || runAttemptCount > 0
        val entity = downloadDao.getById(downloadId) ?: return@withContext Result.failure() // deleted meanwhile

        // Battery rule: wait (queued) until charging or the level is back above the user's minimum.
        minBatteryPercent = userPreferences.downloadRules.first().minBatteryPercent
        if (batteryTooLow()) {
            downloadDao.updateStatus(downloadId, DownloadStatus.QUEUED.name)
            return@withContext Result.retry()
        }

        runCatching { setForeground(foregroundInfo(title, entity.progress)) }

        try {
            val dir = File(applicationContext.getExternalFilesDir(null), Constants.DOWNLOAD_DIR_NAME).apply { mkdirs() }
            val safeName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
            val wanted = File(dir, "${safeName}_${downloadId.take(6)}.$extension")
            // A previous partial file keeps its path; a new stream/extension starts clean.
            val previous = entity.filePath?.let(::File)
            if (previous != null && previous.absolutePath != wanted.absolutePath) runCatching { previous.delete() }
            val outFile = wanted
            if (!resume) runCatching { outFile.delete() }
            downloadDao.updateFilePath(downloadId, outFile.absolutePath)

            val audioUrl = inputData.getString(KEY_AUDIO_URL)
            if (audioUrl == null) {
                fetch(streamUrl, outFile, resume, downloadId, title, entity.progress, entity.totalBytes, 0, 100)
            } else {
                // HD: video-only + audio, then a lossless remux into one MP4. Parts keep their own
                // files so pause/resume continues each with a Range request.
                val videoPart = File(dir, "${outFile.name}.video.part")
                val audioPart = File(dir, "${outFile.name}.audio.part")
                if (!resume) { videoPart.delete(); audioPart.delete() }
                fetch(streamUrl, videoPart, resume, downloadId, title, entity.progress, 0, 0, 85)
                fetch(audioUrl, audioPart, resume, downloadId, title, 85, 0, 85, 97)
                if (isStopped) throw CancellationException("stopped")
                Mp4Merger.merge(videoPart, audioPart, outFile)
                videoPart.delete(); audioPart.delete()
                downloadDao.updateRunning(downloadId, 100, outFile.length(), outFile.length())
            }
            // Deleted while finishing? Then don't resurrect it.
            if (downloadDao.getById(downloadId) == null) {
                runCatching { outFile.delete() }
                return@withContext Result.failure()
            }
            downloadDao.markCompleted(downloadId, DownloadStatus.COMPLETED.name, outFile.absolutePath)
            Result.success()
        } catch (t: BatteryLowException) {
            // Keep the partial file; WorkManager retries in a few minutes (see doWork's battery check).
            downloadDao.updateStatus(downloadId, DownloadStatus.QUEUED.name)
            Result.retry()
        } catch (t: Throwable) {
            // Stopped by pause/delete: the repository already set the right state (PAUSED, or
            // the row is gone) — don't overwrite it with FAILED. Keep the partial file.
            if (!isStopped) downloadDao.markFailedIfActive(downloadId)
            Result.failure()
        }
    }

    /**
     * Downloads [url] into [file], resuming with a Range request when [resume] and a partial file
     * exists. Progress is reported mapped into [from]..[to] % of the whole download.
     */
    private suspend fun fetch(
        url: String, file: File, resume: Boolean, downloadId: String, title: String,
        knownProgress: Int, knownTotal: Long, from: Int, to: Int
    ) {
        val existing = if (resume && file.exists()) file.length() else 0L
        downloadDao.updateRunning(downloadId, if (existing > 0) knownProgress else from, existing, knownTotal)
        val request = Request.Builder().url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            // Range past the end: this part was already complete before the pause.
            if (response.code == 416 && existing > 0) { downloadDao.updateRunning(downloadId, to, existing, existing); return }
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("Empty body")
            // 206 = the server honoured the Range → append. Anything else → start over.
            val partial = response.code == 206 && existing > 0 &&
                rangeTotal(response.header("Content-Range")).let { t -> t == null || knownTotal <= 0 || t == knownTotal || from > 0 || to < 100 }
            val startAt = if (partial) existing else 0L
            val total = body.contentLength().takeIf { it > 0 }?.let { it + startAt } ?: -1L
            var written = startAt
            var lastPercent = -1
            var lastWrite = 0L
            body.byteStream().use { input ->
                java.io.FileOutputStream(file, partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) throw CancellationException("stopped")
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        val part = if (total > 0) (written * 100 / total).toInt() else 0
                        val percent = from + (to - from) * part / 100
                        val now = System.currentTimeMillis()
                        if (percent != lastPercent || now - lastWrite > 1_000) {
                            if (batteryTooLow()) throw BatteryLowException()
                            lastPercent = percent
                            lastWrite = now
                            downloadDao.updateRunning(downloadId, percent, written, total.coerceAtLeast(0))
                            runCatching { setForeground(foregroundInfo(title, percent)) }
                        }
                    }
                }
            }
            if (total > 0 && written < total) error("Connection closed early ($written of $total bytes)")
            downloadDao.updateRunning(downloadId, to, written, if (total > 0) total else written)
        }
    }

    private class BatteryLowException : RuntimeException("Battery below the download limit")

    private var minBatteryPercent = 0

    /** True when the user set a minimum battery level, we're below it and not charging. */
    private fun batteryTooLow(): Boolean {
        if (minBatteryPercent <= 0) return false
        val i = applicationContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        if (level < 0 || scale <= 0) return false
        return !charging && level * 100 / scale < minBatteryPercent
    }

    /** "bytes 1000-4999/5000" → 5000 (null when absent or "*"). */
    private fun rangeTotal(header: String?): Long? = header?.substringAfter('/', "")?.toLongOrNull()

    companion object {
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_STREAM_URL = "stream_url"
        /** Present for HD downloads: audio stream to merge with the video-only [KEY_STREAM_URL]. */
        const val KEY_AUDIO_URL = "audio_url"
        const val KEY_TITLE = "title"
        const val KEY_AUDIO_ONLY = "audio_only"
        const val KEY_EXTENSION = "extension"
        /** Append to the existing partial file with an HTTP Range request. */
        const val KEY_RESUME = "resume"
    }
}
