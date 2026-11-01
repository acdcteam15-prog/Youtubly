package com.youtubly.app.domain.playlist

/** Pure list helpers for playlists (unit-tested). */
object PlaylistOrder {
    /** [list] with the element at [from] moved to [to]; out-of-range indices return [list] unchanged. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }

    /**
     * Shuffle for "Shuffle play": when [first] is in the list it's played first (tapping a
     * video, then shuffling the rest); otherwise a plain shuffle.
     */
    fun <T> shuffled(list: List<T>, first: T? = null, random: kotlin.random.Random = kotlin.random.Random): List<T> {
        if (first == null || first !in list) return list.shuffled(random)
        return listOf(first) + (list - first).shuffled(random)
    }
}
