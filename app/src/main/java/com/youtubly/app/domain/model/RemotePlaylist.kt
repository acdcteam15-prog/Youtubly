package com.youtubly.app.domain.model

/**
 * One page of a list, plus an opaque handle for the next page (null = that was the last one).
 * Callers pass [next] back unchanged; only the data layer knows what it is.
 */
data class ListPage<T>(val items: List<T>, val next: Any?)

/** A playlist as shown in a channel's Playlists tab. */
data class ChannelPlaylist(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val videoCount: Long,
    val uploaderName: String?
)

/** A YouTube playlist's header and its first page of videos. */
data class RemotePlaylist(
    val url: String,
    val title: String,
    val uploaderName: String?,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val videoCount: Long,
    val first: ListPage<Video>
)

/** Tabs of a channel page that this app can show. */
enum class ChannelTabKind { VIDEOS, LIVE, SHORTS, PLAYLISTS }
