package com.youtubly.app.playback

import androidx.media3.exoplayer.ExoPlayer
import com.youtubly.app.domain.model.SponsorSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SponsorBlockSkipper(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val onSkipped: (SponsorSegment) -> Unit = {}
) {
    private var job: Job? = null

    fun start(segments: List<SponsorSegment>) {
        stop()
        if (segments.isEmpty()) return
        job = scope.launch {
            while (isActive) {
                delay(400)
                val pos = player.currentPosition / 1000.0
                val hit = segments.firstOrNull { pos >= it.startSeconds && pos < it.endSeconds - 0.3 }
                if (hit != null && player.isPlaying) {
                    player.seekTo((hit.endSeconds * 1000).toLong())
                    onSkipped(hit)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
