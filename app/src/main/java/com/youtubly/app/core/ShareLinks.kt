package com.youtubly.app.core

/**
 * Builds the links Youtubly shares. Pure Kotlin, unit-tested.
 *  - [clean] drops tracking and playback junk (si, pp, feature, list index, …) but keeps the
 *    video id, playlist id and start time.
 *  - [short] turns a watch link into youtu.be/<id>.
 *  - [at] sets the start time (YouTube links can't carry an end time, so loops share their start).
 */
object ShareLinks {
    private val VIDEO_ID = Regex("""(?:v=|youtu\.be/|/shorts/|/live/|/embed/)([\w-]{11})""")
    private val KEEP = setOf("v", "list", "t")

    fun videoId(url: String): String? = VIDEO_ID.find(url)?.groupValues?.get(1)

    fun clean(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val base = url.substring(0, q)
        val keep = url.substring(q + 1).substringBefore('#').split('&')
            .filter { it.substringBefore('=') in KEEP && it.contains('=') }
        return if (keep.isEmpty()) base else base + "?" + keep.joinToString("&")
    }

    /** youtu.be/<id> (keeps t= and list=); anything that isn't a video link is only cleaned. */
    fun short(url: String): String {
        val id = videoId(url) ?: return clean(url)
        val extra = clean(url).substringAfter('?', "").split('&').filter { it.startsWith("t=") || it.startsWith("list=") }
        return "https://youtu.be/$id" + if (extra.isEmpty()) "" else "?" + extra.joinToString("&")
    }

    /** Sets (or removes, when [sec] ≤ 0) the start time. */
    fun at(url: String, sec: Int): String {
        val q = url.indexOf('?')
        val base = if (q < 0) url else url.substring(0, q)
        val params = if (q < 0) emptyList() else url.substring(q + 1).split('&').filter { it.isNotEmpty() && !it.startsWith("t=") }
        val all = if (sec > 0) params + "t=${sec}s" else params
        return if (all.isEmpty()) base else base + "?" + all.joinToString("&")
    }

    fun build(url: String, short: Boolean, startSec: Int): String = at(if (short) short(url) else clean(url), startSec)

    fun clock(sec: Int): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

    /** Message for a loop: the link starts at A, the text says where to stop. */
    fun loopMessage(link: String, aSec: Int, bSec: Int): String = "Watch ${clock(aSec)}–${clock(bSec)}: $link"

    /** A local playlist as plain text: title, then one numbered short link per video. */
    fun playlistText(title: String, videos: List<Pair<String, String>>): String = buildString {
        append(title).append(" (").append(videos.size).append(" videos)\n")
        videos.forEachIndexed { i, (name, url) -> append(i + 1).append(". ").append(name).append("\n   ").append(short(url)).append('\n') }
    }.trimEnd()
}
