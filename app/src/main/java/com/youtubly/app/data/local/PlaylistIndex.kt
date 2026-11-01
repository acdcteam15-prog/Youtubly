package com.youtubly.app.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which videos each YouTube playlist contained the last time it was opened, so a playlist card
 * in a channel's Playlists tab can show "12 of 61 watched" without fetching the playlist again.
 * Plain SharedPreferences: small, and only ever read synchronously while drawing a card.
 */
@Singleton
class PlaylistIndex @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("playlist_index", Context.MODE_PRIVATE)

    fun idsFor(playlistUrl: String): List<String> =
        prefs.getString(keyOf(playlistUrl), null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()

    fun save(playlistUrl: String, videoIds: List<String>) {
        prefs.edit().putString(keyOf(playlistUrl), videoIds.take(MAX_IDS).joinToString(",")).apply()
    }

    companion object {
        private const val MAX_IDS = 600

        /** The playlist id when the URL has one (so different URL shapes of the same list share an entry). */
        fun keyOf(url: String): String =
            Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1) ?: url
    }
}
