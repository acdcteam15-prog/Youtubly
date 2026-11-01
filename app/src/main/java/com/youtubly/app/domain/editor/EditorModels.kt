package com.youtubly.app.domain.editor

/**
 * Video editor (Phase 1: trim). Everything in this package is plain Kotlin with no Android
 * imports, so the rules the editor relies on are covered by JVM unit tests.
 */

/** A stretch of the source video in milliseconds, [startMs] inclusive to [endMs] exclusive. */
data class TimeRange(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0) { "startMs must be >= 0 (was $startMs)" }
        require(endMs >= startMs) { "endMs ($endMs) must be >= startMs ($startMs)" }
    }

    val durationMs: Long get() = endMs - startMs

    companion object {
        /** Builds a valid range from arbitrary (e.g. stored) values instead of throwing. */
        fun safe(startMs: Long, endMs: Long): TimeRange {
            val s = startMs.coerceAtLeast(0)
            return TimeRange(s, endMs.coerceAtLeast(s))
        }
    }
}

/** The two ends of the trim selection. */
enum class TrimHandle { START, END }

/**
 * Everything the user has decided about one video. Undo/redo stores whole snapshots of
 * this, so later phases add fields here (removed segments, speed, …) and get undo for free.
 */
data class EditState(
    val trim: TimeRange,
    /** Parts removed from inside the video (Phase 2). Stored normalized: sorted, non-overlapping. */
    val cuts: List<TimeRange> = emptyList()
) {
    /** What the export keeps: the trim range minus the cuts. */
    val keep: List<TimeRange> get() = Segments.subtract(trim, cuts)

    /** True when exporting would just copy the whole video. */
    fun isUnchanged(durationMs: Long): Boolean = keep == listOf(TimeRange(0, durationMs.coerceAtLeast(0)))

    companion object {
        fun initial(durationMs: Long) = EditState(TimeRange(0, durationMs.coerceAtLeast(0)))
    }
}

/** A saved video opened in the editor. */
data class EditSource(
    val downloadId: String,
    val path: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
    /** Displayed frame size (rotation applied); 0 when unknown. */
    val width: Int = 0,
    val height: Int = 0,
    /** No video track: edited and exported as audio (.m4a). */
    val audioOnly: Boolean = false
)

enum class EditJobStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING

    companion object {
        /** Unknown / corrupted values read from the database count as failed, never crash. */
        fun parse(value: String?): EditJobStatus = entries.firstOrNull { it.name == value } ?: FAILED
    }
}

/** One export in the edit queue. */
data class EditJob(
    val id: String,
    val sourceDownloadId: String,
    val title: String,
    val thumbnailUrl: String?,
    val sourceDurationMs: Long,
    val range: TimeRange,
    val status: EditJobStatus,
    val progress: Int,
    /** content:// URI of the saved MP4 once completed. */
    val outputUri: String?,
    val outputName: String?,
    val error: String?,
    val createdAt: Long,
    /** e.g. "Merge 3 videos · 12:04 · 720p" */
    val summary: String
)

/** Snapshot (frame grab) image format. */
enum class SnapshotFormat(val extension: String, val mimeType: String) {
    JPG("jpg", "image/jpeg"),
    PNG("png", "image/png")
}

/** What the Edit tab is asked to open (Saved → Edit, or Share → Youtubly from another app). */
sealed interface EditorRequest {
    data class OpenDownload(val downloadId: String) : EditorRequest
    /** content:// URI of a video shared from another app; copied in before editing. */
    data class ImportShared(val uri: String) : EditorRequest
    /** A shared video already copied into app storage (done in the activity, where the read grant is valid). */
    data class OpenImported(val path: String, val title: String) : EditorRequest
}
