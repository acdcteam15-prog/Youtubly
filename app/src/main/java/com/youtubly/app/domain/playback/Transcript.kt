package com.youtubly.app.domain.playback

/** One line of a transcript: shown from [startMs] until the next line starts. */
data class TranscriptCue(val startMs: Long, val text: String)

/** Turns a WebVTT caption file (what YouTube serves for subtitles) into transcript lines. Pure Kotlin, unit-tested. */
object Transcript {
    private val TIMING = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->""")
    private val TAGS = Regex("""<[^>]*>""")

    fun parseVtt(text: String): List<TranscriptCue> {
        val cues = ArrayList<TranscriptCue>()
        var previous: Set<String> = emptySet()
        for (block in text.replace("\r\n", "\n").split(Regex("\n{2,}"))) {
            val lines = block.split('\n')
            val at = lines.indexOfFirst { TIMING.containsMatchIn(it) }
            if (at < 0) continue
            val m = TIMING.find(lines[at])!!
            val (h, mi, s, ms) = m.destructured
            val start = (h.toLongOrNull() ?: 0L) * 3_600_000L + mi.toLong() * 60_000L + s.toLong() * 1_000L + ms.toLong()
            val body = lines.drop(at + 1).map { clean(it) }.filter { it.isNotBlank() }
            // YouTube's automatic captions repeat the previous line inside every new cue: keep only what is new.
            val fresh = body.filter { it !in previous }
            previous = body.toSet()
            if (fresh.isEmpty()) continue
            cues += TranscriptCue(start, fresh.joinToString(" "))
        }
        return cues
    }

    private fun clean(line: String): String = TAGS.replace(line, "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&nbsp;", " ").trim()

    /** Index of the line being spoken at [positionMs], or -1 before the first one. */
    fun indexAt(cues: List<TranscriptCue>, positionMs: Long): Int = cues.indexOfLast { it.startMs <= positionMs }

    /** Lines containing every word of [query] (Arabic-aware), keeping their original index. */
    fun search(cues: List<TranscriptCue>, query: String): List<Pair<Int, TranscriptCue>> =
        if (query.isBlank()) cues.withIndex().map { it.index to it.value }
        else cues.withIndex().filter { com.youtubly.app.core.SearchText.matches(it.value.text, query) }.map { it.index to it.value }

    /** "12:40" or "1:02:03". */
    fun stamp(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/** Speed for a video: its own remembered speed, else the channel's, else the global one. */
fun pickSpeed(video: Float?, channel: Float?, global: Float): Float = video ?: channel ?: global
