package com.youtubly.app.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.youtubly.app.domain.model.DownloadOptions
import com.youtubly.app.domain.model.DownloadStatus
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.DownloadRepository
import com.youtubly.app.domain.usecase.GetStreamInfoUseCase
import com.youtubly.app.domain.usecase.StartDownloadUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a whole playlist: for each video, looks up its streams, picks the option that matches the chosen
 * [Quality] and queues it through the normal download path, so the Wi-Fi-only / battery / overnight rules and
 * pause / resume all apply to every item. Videos already saved or downloading are skipped.
 *
 * The looking-up runs here, in the app process, one video at a time; the transfers themselves run in
 * WorkManager. If Android kills the app while this is still queuing, the remaining videos are not queued
 * (the ones already queued keep downloading) — run it again and the finished ones are skipped.
 */
@Singleton
class PlaylistDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getStreamInfo: GetStreamInfoUseCase,
    private val startDownload: StartDownloadUseCase,
    private val downloads: DownloadRepository
) {
    enum class Quality(val label: String, val maxHeight: Int?) {
        P1080("Up to 1080p", 1080), P720("Up to 720p", 720), P480("Up to 480p", 480), P360("Up to 360p", 360),
        AUDIO("Audio only", null)
    }

    data class Progress(val title: String, val total: Int, val handled: Int, val queued: Int, val skipped: Int, val failed: Int, val current: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    val isRunning: Boolean get() = job?.isActive == true

    /** Starts queuing [videos]; false if another playlist is still being queued. */
    fun start(title: String, videos: List<Video>, quality: Quality): Boolean {
        if (isRunning || videos.isEmpty()) return false
        job = scope.launch {
            var queued = 0; var skipped = 0; var failed = 0
            try {
                val existing = downloads.observeDownloads().first()
                    .filter { it.status != DownloadStatus.FAILED }.map { it.videoId }.toSet()
                videos.forEachIndexed { i, v ->
                    ensureActive()
                    _progress.value = Progress(title, videos.size, i, queued, skipped, failed, v.title)
                    if (v.id in existing) { skipped++; return@forEachIndexed }
                    val info = getStreamInfo(v.url).getOrNull()
                    val option = info?.let { DownloadOptions.pickFor(DownloadOptions.build(it), quality.maxHeight) }
                    if (info == null || option == null) { failed++; return@forEachIndexed }
                    runCatching {
                        startDownload(info.video.id, info.video.url, info.video.title, info.video.thumbnailUrl, option, info.video.uploaderUrl)
                    }.onSuccess { queued++ }.onFailure { if (it is CancellationException) throw it else failed++ }
                    delay(250)   // be gentle with YouTube
                }
                toast(summary(queued, skipped, failed, stopped = false))
            } catch (e: CancellationException) {
                toast(summary(queued, skipped, failed, stopped = true))
                throw e
            } finally {
                _progress.value = null
            }
        }
        return true
    }

    fun cancel() { job?.cancel() }

    private fun summary(queued: Int, skipped: Int, failed: Int, stopped: Boolean): String = buildString {
        append(if (stopped) "Stopped · " else "")
        append("$queued queued")
        if (skipped > 0) append(", $skipped already saved")
        if (failed > 0) append(", $failed couldn't be queued")
    }

    private fun toast(text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
