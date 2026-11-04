package com.youtubly.app.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.domain.download.AutoDownloadPlanner
import com.youtubly.app.domain.repository.ChannelRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Every few hours, on Wi-Fi with the battery not low: for each channel with "Download new videos" switched on, looks
 * for videos that appeared since the last look and downloads them (the channel's own quality / audio-only choice,
 * 720p otherwise). The first look only remembers what is there, so old videos are never fetched.
 */
@HiltWorker
class ChannelAutoDownloadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val prefs: UserPreferences,
    private val channels: ChannelRepository,
    private val downloader: PlaylistDownloader
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val rules = prefs.channelDefaults.first().filterValues { it.autoDownload }
        if (rules.isEmpty()) return Result.success()
        val seenStore = appContext.getSharedPreferences("auto_download_seen", Context.MODE_PRIVATE)
        for ((key, rule) in rules) {
            val channel = channels.getChannel(AutoDownloadPlanner.urlForKey(key)).getOrNull() ?: continue
            val seen = seenStore.getString(key, null)?.split(',')?.filter { it.isNotBlank() }
            val plan = AutoDownloadPlanner.plan(channel.videos.take(10), seen)
            if (plan.fresh.isNotEmpty()) {
                val quality = when {
                    rule.audioOnly == true -> PlaylistDownloader.Quality.AUDIO
                    rule.qualityHeight != null -> PlaylistDownloader.Quality.entries
                        .firstOrNull { it.maxHeight != null && it.maxHeight <= rule.qualityHeight } ?: PlaylistDownloader.Quality.P360
                    else -> PlaylistDownloader.Quality.P720
                }
                // A download batch is already running: leave these as "new" and try again next time.
                if (!downloader.start("New from ${channel.name}", plan.fresh, quality)) continue
            }
            seenStore.edit().putString(key, plan.seenAfter.joinToString(",")).apply()
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "channel-auto-download"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ChannelAutoDownloadWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
