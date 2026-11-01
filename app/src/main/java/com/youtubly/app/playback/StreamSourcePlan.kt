package com.youtubly.app.playback

import com.youtubly.app.domain.model.AudioStreamOption
import com.youtubly.app.domain.model.StreamInfoResult
import com.youtubly.app.domain.model.VideoStreamOption
import com.youtubly.app.domain.model.originalOnly

/**
 * One playable candidate, described without any ExoPlayer/MediaSource types so this whole
 * file — and the selection logic below — can be exercised by plain JVM unit tests. Each plan
 * maps 1:1 to a MediaSource that PlayerController builds from it.
 */
sealed interface StreamPlan {
    /** Muxed audio+video from a single progressive URL (legacy, or audio-only playback). */
    data class Progressive(val url: String) : StreamPlan

    /** Separately-fetched video and audio streams, merged into one playable source. */
    data class VideoPlusAudio(val videoUrl: String, val audioUrl: String) : StreamPlan

    data class Hls(val url: String) : StreamPlan

    data class Dash(val url: String) : StreamPlan
}

/**
 * Picks the best available audio stream — prefers M4A (the most broadly compatible container)
 * at the highest bitrate, falling back to whatever's highest-bitrate if no M4A track exists.
 */
fun selectBestAudio(audioStreams: List<AudioStreamOption>): AudioStreamOption? {
    val sorted = audioStreams.originalOnly().sortedByDescending { it.averageBitrate }
    return sorted.firstOrNull { it.format.contains("M4A", ignoreCase = true) } ?: sorted.firstOrNull()
}

/** The second-best audio stream, used as a one-shot fallback if the best one 403s/404s. */
fun selectFallbackAudio(audioStreams: List<AudioStreamOption>): AudioStreamOption? {
    val sorted = audioStreams.originalOnly().sortedByDescending { it.averageBitrate }
    return sorted.getOrNull(1)
}

/** Video-only streams at an exact height, preferring MPEG-4 over other containers at that
 *  height (matches what fixed-quality selection expects: "1080p, please"). */
fun selectVideoAtHeight(videoStreams: List<VideoStreamOption>, height: Int): VideoStreamOption? =
    videoStreams
        .filter { it.isVideoOnly && it.height == height }
        .sortedByDescending { if (it.format.contains("MPEG_4", ignoreCase = true)) 1 else 0 }
        .firstOrNull()

/** Highest-height MPEG-4 video-only stream at or below 1080p, for automatic (non-fixed)
 *  quality selection — a hard cap, since higher usually means VP9/AV1 the device may not
 *  decode as reliably, and 1080p is already more than enough for a phone/PIP window. */
fun selectAutoVideo(videoStreams: List<VideoStreamOption>, maxHeight: Int? = null): VideoStreamOption? {
    val mp4 = videoStreams.filter { it.isVideoOnly && it.format.contains("MPEG_4", ignoreCase = true) && it.height in 1..AUTO_MAX_HEIGHT }
    val cap = minOf(maxHeight ?: AUTO_MAX_HEIGHT, AUTO_MAX_HEIGHT)
    // Highest stream within the cap; if a network cap is below everything on offer (e.g. a
    // 360p cap on a video whose smallest MP4 is 480p) take the smallest stream instead of none.
    return mp4.filter { it.height <= cap }.maxByOrNull { it.height } ?: mp4.minByOrNull { it.height }
}

private const val AUTO_MAX_HEIGHT = 1080

/**
 * Ordered best-first list of playable plans for [info] under [options]. Mirrors
 * PlayerController's original inline logic exactly — see that class's kdoc for the ordering
 * rationale (HLS first as the most reliable client-served format, then DASH, then a fixed or
 * auto-selected video-only+audio pair, then legacy muxed progressive as the last resort).
 * [PlaybackOptions.maxHeight] (network quality cap) limits the auto video-only pick here;
 * adaptive HLS/DASH are capped through ExoPlayer's track-selection max video size instead.
 * Caption/translation selection isn't part of this — that's applied to whichever MediaSource
 * is actually built from the winning plan, since captions don't change which stream wins.
 */
fun buildStreamPlans(info: StreamInfoResult, options: PlaybackOptions): List<StreamPlan> {
    val bestAudio = selectBestAudio(info.audioStreams)

    if (options.audioOnly) {
        if (bestAudio == null) return emptyList()
        val fallback = selectFallbackAudio(info.audioStreams)
        return listOfNotNull(
            StreamPlan.Progressive(bestAudio.url),
            fallback?.let { StreamPlan.Progressive(it.url) }
        )
    }

    val plans = mutableListOf<StreamPlan>()

    val fixedHeight = options.qualityHeight
    if (fixedHeight != null && bestAudio != null) {
        selectVideoAtHeight(info.videoStreams, fixedHeight)?.let {
            plans += StreamPlan.VideoPlusAudio(it.url, bestAudio.url)
        }
    }

    // HLS/DASH manifests carry every audio language and ExoPlayer may pick a dub (e.g. the
    // phone's language). When a video has dubs, play the explicit original-audio pair instead;
    // adaptive stays for live streams and single-language videos.
    val hasDubs = info.audioStreams.any { !it.isOriginal }
    if (!hasDubs || info.video.isLive) {
        info.hlsUrl?.let { plans += StreamPlan.Hls(it) }
        info.dashUrl?.let { plans += StreamPlan.Dash(it) }
    }

    val autoVideo = selectAutoVideo(info.videoStreams, options.maxHeight)
    if (autoVideo != null && bestAudio != null) {
        plans += StreamPlan.VideoPlusAudio(autoVideo.url, bestAudio.url)
    }

    info.videoStreams.firstOrNull { !it.isVideoOnly }?.let { plans += StreamPlan.Progressive(it.url) }

    return plans
}
