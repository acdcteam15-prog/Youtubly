package com.youtubly.app.domain.model

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

data class DownloadItem(
    val id: String,
    val videoId: String,
    val title: String,
    val thumbnailUrl: String?,
    val filePath: String?,
    val progress: Int,
    val status: DownloadStatus,
    val isAudioOnly: Boolean,
    val qualityLabel: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    /** 0..100, the higher of offline playback and online watch history. */
    val watchedPercent: Int = 0,
    /** Channel URL (null for old downloads until resolved once online). */
    val uploaderUrl: String? = null,
    /** Exact offline resume point and file duration (ms); 0 until played offline once. */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val createdAt: Long = 0
) {
    /** Where the bar sits, 0..1 — exact when known, else the coarse watched percent. */
    val resumeFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else (watchedPercent / 100f).coerceIn(0f, 1f)

    /** Resume point to start from: null when finished (≥95 %), barely started, or unknown. */
    val resumeAtMs: Long?
        get() = when {
            durationMs > 0 && positionMs >= 5_000 && positionMs < durationMs - 10_000 && resumeFraction < 0.95f -> positionMs
            else -> null
        }
}

/** Space used by downloads (actual files on disk) and free space on that volume. */
data class DownloadStorage(val usedBytes: Long, val freeBytes: Long)
