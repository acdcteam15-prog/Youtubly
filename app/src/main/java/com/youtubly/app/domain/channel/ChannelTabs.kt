package com.youtubly.app.domain.channel

import com.youtubly.app.domain.model.Video

/** Sort for a channel's video lists. The server order is newest first. */
enum class VideoSort(val label: String) {
    LATEST("Latest"), POPULAR("Popular"), OLDEST("Oldest");

    /**
     * Applied to the videos loaded so far: YouTube offers no sort option to this app, so Popular / Oldest
     * are exact only once the whole list is loaded (the screen offers "Load all" for that).
     */
    fun apply(videos: List<Video>): List<Video> = when (this) {
        LATEST -> videos
        POPULAR -> videos.sortedByDescending { it.viewCount }   // unknown counts (-1) sink to the bottom
        OLDEST -> videos.asReversed()
    }
}

/** How much of a playlist has been watched, from the watch-history progress map (videoId → 0..1). */
object PlaylistProgress {
    const val WATCHED_FRACTION = 0.9f
    private const val STARTED_FRACTION = 0.02f

    fun isWatched(progress: Float) = progress >= WATCHED_FRACTION

    fun watchedCount(ids: List<String>, progress: Map<String, Float>): Int =
        ids.count { isWatched(progress[it] ?: 0f) }

    /**
     * Where to continue: the last video (in playlist order) that was started; that one if it isn't finished,
     * otherwise the one after it. Null when nothing was started, or everything up to the end is done.
     */
    fun continueIndex(ids: List<String>, progress: Map<String, Float>): Int? {
        val last = ids.indexOfLast { (progress[it] ?: 0f) >= STARTED_FRACTION }
        if (last < 0) return null
        val next = if (isWatched(progress[ids[last]] ?: 0f)) last + 1 else last
        return next.takeIf { it in ids.indices }
    }
}

/** Auto-remove playlists: which videos are due to leave their list because they've been watched. */
object PlaylistAutoFinish {
    /** (playlistId → videoIds) for every candidate whose video is in [watched]. Empty when nothing is due. */
    fun due(candidates: List<Pair<Long, String>>, watched: Set<String>): Map<Long, Set<String>> =
        candidates.filter { it.second in watched }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
}
