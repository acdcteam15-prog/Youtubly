package com.youtubly.app.domain.model

/**
 * One choice in the download quality picker: a muxed video+audio file (YouTube serves these
 * only up to 360p/720p), audio alone, or — for higher resolutions — a separate H.264 video
 * stream plus the original audio, downloaded both and joined into one MP4 on the phone
 * (lossless remux, no re-encoding).
 */
data class DownloadOption(
    val label: String,
    val url: String,
    val audioOnly: Boolean,
    /** Stable description of the stream, used to find the same one again with fresh URLs:
     *  "v|<resolution>|<format>" or "a|<bitrate>|<format>". */
    val formatKey: String,
    val extension: String,
    /** Set for merged options: the audio stream joined with [url] (video-only). */
    val audioUrl: String? = null
) {
    val isMerged: Boolean get() = audioUrl != null
}

object DownloadOptions {

    fun build(info: StreamInfoResult): List<DownloadOption> {
        val video = info.videoStreams
            .filter { !it.isVideoOnly }
            .distinctBy { it.resolution to it.format }
            .sortedWith(compareByDescending<VideoStreamOption> { it.height }.thenBy { if (isMp4(it.format)) 0 else 1 })
            .map {
                DownloadOption(
                    label = "${it.resolution} · ${prettyFormat(it.format)}",
                    url = it.url,
                    audioOnly = false,
                    formatKey = "v|${it.resolution}|${it.format}",
                    extension = extensionFor(it.format, audio = false)
                )
            }
        // Higher than the best single-file stream: H.264 video-only + original M4A audio, remuxed.
        // H.264 only — AV1/VP9 can't go into MP4 with MediaMuxer on every Android version.
        val bestMuxed = video.maxOfOrNull { rank(it.formatKey) } ?: 0
        val mergeAudio = info.audioStreams.originalOnly()
            .filter { it.format.equals("M4A", true) }
            .maxByOrNull { it.averageBitrate }
        val merged = if (mergeAudio == null) emptyList() else info.videoStreams
            .filter { it.isVideoOnly && isMp4(it.format) && it.height > bestMuxed && it.height <= MAX_MERGE_HEIGHT }
            .filter { it.codec == null || it.codec.startsWith("avc", ignoreCase = true) }
            .distinctBy { it.resolution }
            .sortedByDescending { it.height }
            .map {
                DownloadOption(
                    label = "${it.resolution} · MP4 (HD)",
                    url = it.url,
                    audioOnly = false,
                    formatKey = "m|${it.resolution}|${it.format}",
                    extension = "mp4",
                    audioUrl = mergeAudio.url
                )
            }

        // Fallback for videos without H.264 video-only streams (or without M4A audio): VP9 video-only +
        // Opus audio joined into a WebM. MediaMuxer writes WebM natively, so this is lossless too, and it
        // reaches 1440p/4K, which YouTube only serves as VP9/AV1.
        val mp4Heights = merged.map { rank(it.formatKey) }.toSet()
        val opusAudio = info.audioStreams.originalOnly()
            .filter { isWebmAudio(it.format) }
            .maxByOrNull { it.averageBitrate }
        val mergedWebm = if (opusAudio == null) emptyList() else info.videoStreams
            .filter { it.isVideoOnly && isWebm(it.format) && it.height > bestMuxed && it.height <= MAX_WEBM_HEIGHT && it.height !in mp4Heights }
            .filter { it.codec == null || it.codec.startsWith("vp", ignoreCase = true) }
            .distinctBy { it.resolution }
            .sortedByDescending { it.height }
            .map {
                DownloadOption(
                    label = "${it.resolution} · WebM (HD)",
                    url = it.url,
                    audioOnly = false,
                    formatKey = "m|${it.resolution}|${it.format}",
                    extension = "webm",
                    audioUrl = opusAudio.url
                )
            }

        val audio = info.audioStreams.originalOnly()
            .distinctBy { it.averageBitrate to it.format }
            .sortedWith(compareByDescending<AudioStreamOption> { it.averageBitrate }.thenBy { if (it.format.contains("M4A", true)) 0 else 1 })
            .map {
                DownloadOption(
                    label = "${if (it.averageBitrate > 0) "${it.averageBitrate} kbps" else "Audio"} · ${prettyFormat(it.format)}",
                    url = it.url,
                    audioOnly = true,
                    formatKey = "a|${it.averageBitrate}|${it.format}",
                    extension = extensionFor(it.format, audio = true)
                )
            }
        return (merged + mergedWebm).sortedByDescending { rank(it.formatKey) } + video + audio
    }

    /** 1080p is the highest H.264 YouTube serves; above it there's only VP9/AV1. */
    private const val MAX_MERGE_HEIGHT = 1080
    private const val MAX_WEBM_HEIGHT = 2160

    /**
     * Why no HD choice is offered (shown in the download sheet + logged), or null when there is one.
     * Reports what the extractor actually returned, so a "360p only" result can be traced to its cause.
     */
    fun hdDiagnostic(info: StreamInfoResult): String? {
        if (build(info).any { it.isMerged }) return null
        val vo = info.videoStreams.filter { it.isVideoOnly }
        if (vo.isEmpty()) return "YouTube returned no separate HD video streams for this video (only ${info.videoStreams.size} combined stream(s))."
        val tops = vo.groupBy { it.format to (it.codec?.substringBefore('.') ?: "?") }
            .entries.joinToString { (k, v) -> "${v.maxOf { it.height }}p ${prettyFormat(k.first)}/${k.second}" }
        val hasAudio = info.audioStreams.originalOnly().any { it.format.equals("M4A", true) || isWebmAudio(it.format) }
        return if (!hasAudio) "HD video streams exist ($tops) but no usable audio stream to join them with."
        else "HD video streams exist ($tops) but none is at a height above the best single-file stream."
    }

    /** Best default for the old one-tap actions: highest muxed video / best M4A audio. */
    fun default(info: StreamInfoResult, audioOnly: Boolean): DownloadOption? =
        build(info).firstOrNull { it.audioOnly == audioOnly }

    /** The option matching [formatKey] in freshly resolved [info]; otherwise the closest of the
     *  same kind (next lower/equal quality, else the best available). */
    fun match(info: StreamInfoResult, formatKey: String?, audioOnly: Boolean): DownloadOption? {
        val options = build(info).filter { it.audioOnly == audioOnly }
        if (formatKey == null) return options.firstOrNull()
        options.firstOrNull { it.formatKey == formatKey }?.let { return it }
        val wanted = rank(formatKey)
        return options.firstOrNull { rank(it.formatKey) <= wanted } ?: options.firstOrNull()
    }

    /**
     * Picks one option for a bulk download: the best video up to [maxHeight] (falling back to the smallest
     * video when everything is taller), or the best audio when [maxHeight] is null.
     */
    fun pickFor(options: List<DownloadOption>, maxHeight: Int?): DownloadOption? {
        if (maxHeight == null) return options.filter { it.audioOnly }.maxByOrNull { rank(it.formatKey) }
        val videos = options.filter { !it.audioOnly }
        return videos.filter { rank(it.formatKey) <= maxHeight }.maxByOrNull { rank(it.formatKey) }
            ?: videos.minByOrNull { rank(it.formatKey) }
            ?: options.firstOrNull()
    }

    /** Numeric quality from a format key (height for video, bitrate for audio). */
    private fun rank(key: String): Int =
        key.split('|').getOrNull(1)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0

    private fun isWebm(format: String) = format.equals("WEBM", true)
    private fun isWebmAudio(format: String) = format.contains("WEBMA", true) || format.equals("OPUS", true)

    private fun isMp4(format: String) = format.contains("MPEG_4", true) || format.contains("MP4", true)

    fun prettyFormat(format: String): String = when {
        isMp4(format) -> "MP4"
        format.equals("M4A", true) -> "M4A"
        format.equals("WEBMA", true) || format.equals("WEBMA_OPUS", true) || format.equals("OPUS", true) -> "Opus"
        format.equals("WEBM", true) -> "WebM"
        format.contains("3GP", true) -> "3GP"
        else -> format
    }

    fun extensionFor(format: String, audio: Boolean): String = when {
        format.equals("M4A", true) -> "m4a"
        isMp4(format) -> if (audio) "m4a" else "mp4"
        format.contains("WEBM", true) || format.equals("OPUS", true) -> "webm"
        format.contains("3GP", true) -> "3gp"
        else -> if (audio) "m4a" else "mp4"
    }
}
