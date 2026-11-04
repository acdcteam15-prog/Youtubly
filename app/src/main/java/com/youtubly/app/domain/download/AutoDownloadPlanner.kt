package com.youtubly.app.domain.download

import com.youtubly.app.domain.model.Video

/** Decides which videos of a channel are NEW since the last check. Pure and unit-tested. */
object AutoDownloadPlanner {
    const val REMEMBER = 60

    data class Plan(val fresh: List<Video>, val seenAfter: List<String>)

    /**
     * [latest] is the channel's newest videos (newest first). [seen] is what was known at the last check, or null the
     * first time: then nothing is downloaded and the current videos are only remembered, so switching auto-download on
     * never fetches a channel's old backlog.
     */
    fun plan(latest: List<Video>, seen: List<String>?): Plan {
        val ids = latest.map { it.id }
        if (seen == null) return Plan(emptyList(), ids.take(REMEMBER))
        val known = seen.toSet()
        val fresh = latest.filter { it.id !in known }
        return Plan(fresh.reversed(), (ids + seen).distinct().take(REMEMBER))   // oldest of the new ones first
    }

    /** A fetchable address for a stored channel key (a "UC..." id or a normalised path). */
    fun urlForKey(key: String): String = when {
        key.startsWith("UC") && key.length >= 24 -> "https://www.youtube.com/channel/$key"
        key.startsWith("/") -> "https://www.youtube.com$key"
        else -> key
    }
}
