package com.youtubly.app.domain.model

data class Channel(
    val id: String,
    val url: String,
    val name: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val subscriberCount: Long,
    val description: String?,
    val videos: List<Video>,
    /** Which tabs the channel has (Videos, Live, Shorts, Playlists). */
    val tabs: Set<ChannelTabKind> = emptySet(),
    /** Opaque handle for the next page of [videos] (null = no more). */
    val videosNext: Any? = null
)

val Channel.hasPlaylists: Boolean get() = ChannelTabKind.PLAYLISTS in tabs
