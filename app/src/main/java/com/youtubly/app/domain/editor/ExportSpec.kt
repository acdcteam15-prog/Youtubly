package com.youtubly.app.domain.editor

import java.net.URLDecoder
import java.net.URLEncoder

/** One input file and the parts of it to keep, in order. [width]/[height] are the displayed
 *  size (0 when unknown — only needed when clips must be scaled to a common size). */
data class ClipSpec(
    val path: String,
    val durationMs: Long,
    val keep: List<TimeRange>,
    val width: Int = 0,
    val height: Int = 0
)

/** Advanced output controls. null = keep the source's value. */
data class OutputSettings(val maxHeight: Int? = null, val videoBitrateKbps: Int? = null) {
    val isOriginal: Boolean get() = maxHeight == null && videoBitrateKbps == null

    companion object {
        val HEIGHT_CHOICES = listOf(1080, 720, 480, 360)
        const val MIN_KBPS = 300
        const val MAX_KBPS = 8_000
    }
}

/** Everything an export needs. Trim, cut, merge and compress are all this one shape. */
data class ExportSpec(
    val clips: List<ClipSpec>,
    val output: OutputSettings = OutputSettings(),
    /** Phase 3: an audio-only download edited as audio → .m4a output, output settings ignored. */
    val audioOnly: Boolean = false
) {
    val fileExtension: String get() = if (audioOnly) "m4a" else "mp4"
    val mimeType: String get() = if (audioOnly) "audio/mp4" else "video/mp4"

    val totalMs: Long get() = clips.sumOf { Segments.totalMs(it.keep) }

    /** One file, one kept range, no re-encoding requested → fast trim path (mostly copied). */
    val isSimpleTrim: Boolean get() = clips.size == 1 && clips[0].keep.size == 1 && (output.isOriginal || audioOnly)

    /** One whole file, unchanged, no new size/bitrate: a plain format conversion. */
    val isWholeFileConversion: Boolean
        get() = clips.size == 1 && clips[0].keep == listOf(TimeRange(0, clips[0].durationMs)) && (output.isOriginal || audioOnly)

    val isValid: Boolean
        get() = clips.isNotEmpty() && clips.all { it.keep.isNotEmpty() && it.path.isNotBlank() } && totalMs >= TrimRules.MIN_CLIP_MS

    /** Short description for the job list, e.g. "Merge 3 videos · 12:04 · 720p · 2 Mbps". */
    fun summary(): String {
        val what = when {
            isWholeFileConversion -> if (audioOnly) "Convert to M4A" else "Convert to MP4"
            clips.size > 1 -> "Merge ${clips.size} videos"
            clips.size == 1 && clips[0].keep.size > 1 -> "Cut ${clips[0].keep.size - 1} part${if (clips[0].keep.size == 2) "" else "s"} out"
            clips.size == 1 -> clips[0].keep.first().let { "Trim ${EditorFormat.clock(it.startMs)}–${EditorFormat.clock(it.endMs)}" }
            else -> "Export"
        }
        val parts = mutableListOf(what, EditorFormat.clock(totalMs))
        if (audioOnly) {
            parts += "audio"
        } else {
            output.maxHeight?.let { parts += "${it}p" }
            output.videoBitrateKbps?.let { parts += EditorFormat.bitrate(it) }
        }
        return parts.joinToString(" · ")
    }
}

/**
 * Stores an [ExportSpec] in one DB column. Line-based, paths URL-encoded, so no JSON library
 * (and no Android classes) — fully covered by JVM tests. [decode] returns null for anything
 * it can't read rather than throwing.
 */
object ExportSpecCodec {
    private const val VERSION = "1"

    fun encode(spec: ExportSpec): String = buildString {
        append("v=").append(VERSION).append('\n')
        append("out=").append(spec.output.maxHeight ?: "-").append(',').append(spec.output.videoBitrateKbps ?: "-").append('\n')
        if (spec.audioOnly) append("audio=1\n")
        for (c in spec.clips) {
            append("clip=")
                .append(URLEncoder.encode(c.path, "UTF-8")).append('|')
                .append(c.durationMs).append('|')
                .append(c.width).append('|').append(c.height).append('|')
                .append(c.keep.joinToString(",") { "${it.startMs}-${it.endMs}" })
                .append('\n')
        }
    }

    fun decode(text: String?): ExportSpec? {
        if (text.isNullOrBlank()) return null
        return try {
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.firstOrNull() != "v=$VERSION") return null
            var output = OutputSettings()
            var audioOnly = false
            val clips = ArrayList<ClipSpec>()
            for (line in lines.drop(1)) {
                val key = line.substringBefore('=')
                val value = line.substringAfter('=')
                when (key) {
                    "out" -> {
                        val (h, k) = value.split(',').also { require(it.size == 2) }
                        output = OutputSettings(h.toIntOrNull(), k.toIntOrNull())
                    }
                    "audio" -> audioOnly = value == "1"
                    "clip" -> {
                        val f = value.split('|')
                        require(f.size == 5)
                        val keep = f[4].split(',').filter { it.isNotBlank() }.map { r ->
                            val (s, e) = r.split('-').also { require(it.size == 2) }
                            TimeRange(s.toLong(), e.toLong())
                        }
                        clips += ClipSpec(URLDecoder.decode(f[0], "UTF-8"), f[1].toLong(), keep, f[2].toInt(), f[3].toInt())
                    }
                }
            }
            ExportSpec(clips, output, audioOnly).takeIf { it.clips.isNotEmpty() }
        } catch (e: IllegalArgumentException) {
            null // also covers NumberFormatException and TimeRange's require()
        }
    }
}

object OutputMath {
    /**
     * Output frame size: the first clip's size, scaled down (never up) to [maxHeight],
     * both sides even (video encoders require it). Unknown sizes → null (keep source).
     */
    fun targetSize(firstWidth: Int, firstHeight: Int, maxHeight: Int?): Pair<Int, Int>? {
        if (firstWidth <= 0 || firstHeight <= 0) return null
        val h = if (maxHeight != null && maxHeight < firstHeight) maxHeight else firstHeight
        val w = (firstWidth.toLong() * h / firstHeight).toInt()
        return even(w) to even(h)
    }

    private fun even(v: Int) = (v - v % 2).coerceAtLeast(2)

    /** Size estimate: from the chosen bitrate (+128 kbps audio) when set, else proportional. */
    fun estimateBytes(spec: ExportSpec, sourceBytes: List<Long>): Long {
        val kbps = spec.output.videoBitrateKbps.takeUnless { spec.audioOnly }
        if (kbps != null) return ((kbps + 128L) * 1000 / 8) * spec.totalMs / 1000
        var total = 0L
        spec.clips.forEachIndexed { i, c ->
            val bytes = sourceBytes.getOrElse(i) { 0L }
            if (bytes > 0 && c.durationMs > 0) total += (bytes.toDouble() * Segments.totalMs(c.keep) / c.durationMs).toLong()
        }
        return (total * 1.1).toLong()
    }
}

object ConvertRules {
    /** File extension, lower case ("" when none). */
    fun extension(path: String): String =
        path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** True when exporting unchanged would change the format: videos that aren't .mp4 and
     *  audio that isn't .m4a/.mp4 (e.g. WebM/Opus, 3GP). */
    fun needsConversion(path: String, audioOnly: Boolean): Boolean {
        val ext = extension(path)
        return if (audioOnly) ext != "m4a" && ext != "mp4" else ext != "mp4"
    }

    /** Label for the source format shown next to the button, e.g. "WEBM". */
    fun formatLabel(path: String): String = extension(path).uppercase().ifEmpty { "this format" }
}
