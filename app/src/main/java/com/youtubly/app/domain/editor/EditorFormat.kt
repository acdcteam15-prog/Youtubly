package com.youtubly.app.domain.editor

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object EditorFormat {
    /** 65_000 → "1:05", 3_725_000 → "1:02:05"; with [tenths] → "1:05.3". Negative → 0. */
    fun clock(ms: Long, tenths: Boolean = false): String {
        val safe = ms.coerceAtLeast(0)
        val totalSec = safe / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val base = if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
        return if (tenths) "$base.${(safe % 1000) / 100}" else base
    }

    /** 2500 → "2.5 Mbps", 800 → "800 kbps". */
    fun bitrate(kbps: Int): String =
        if (kbps >= 1000) {
            val tenths = kbps / 100
            if (tenths % 10 == 0) "${tenths / 10} Mbps" else "${tenths / 10}.${tenths % 10} Mbps"
        } else "$kbps kbps"

    /** 1_572_864 → "1.5 MB". */
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** File name for an exported clip, e.g. "My video_edit_20260925_143012.mp4". */
    fun outputFileName(title: String, now: LocalDateTime, extension: String = "mp4"): String =
        "${safeTitle(title)}_edit_${now.format(stamp)}.$extension"

    /** e.g. "My video_frame_1m05s_20260925_143012.jpg" */
    fun snapshotFileName(title: String, positionMs: Long, now: LocalDateTime, format: SnapshotFormat): String {
        val sec = positionMs.coerceAtLeast(0) / 1000
        return "${safeTitle(title)}_frame_${sec / 60}m${String.format(Locale.US, "%02d", sec % 60)}s_${now.format(stamp)}.${format.extension}"
    }

    private fun safeTitle(title: String) = title.replace(illegal, "_").trim().trim('.').take(60).trim().ifEmpty { "video" }
}

object ExportMath {
    /** Output size estimate for a trim (proportional to the kept length, +10 % container
     *  overhead). Used to refuse an export up front instead of failing halfway. */
    fun estimateOutputBytes(sourceBytes: Long, sourceDurationMs: Long, range: TimeRange): Long {
        if (sourceBytes <= 0 || sourceDurationMs <= 0) return 0
        val fraction = (range.durationMs.toDouble() / sourceDurationMs).coerceIn(0.0, 1.0)
        return (sourceBytes * fraction * 1.1).toLong()
    }

    /** The export writes a temp file, then copies it into Downloads: room for both plus 50 MB. */
    fun hasRoomFor(estimatedBytes: Long, usableBytes: Long): Boolean =
        usableBytes >= estimatedBytes * 2 + 50L * 1024 * 1024

    /** Evenly spread thumbnail times for the timeline strip (centre of each slot). */
    fun frameTimesMs(durationMs: Long, count: Int): List<Long> {
        if (durationMs <= 0 || count <= 0) return emptyList()
        return List(count) { i -> ((i + 0.5) * durationMs / count).toLong() }
    }

    /** Progress 0..100 from a fraction, never reporting 100 before the file is actually saved. */
    fun exportPercent(transformerPercent: Int): Int = transformerPercent.coerceIn(0, 99)
}
