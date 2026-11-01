package com.youtubly.app.domain.repository

import com.youtubly.app.domain.model.Channel
import com.youtubly.app.domain.model.ChannelPlaylist
import com.youtubly.app.domain.model.ChannelTabKind
import com.youtubly.app.domain.model.ListPage
import com.youtubly.app.domain.model.RemotePlaylist
import com.youtubly.app.domain.model.Video

/** One page of in-channel search results. */
data class ChannelSearchPage(val videos: List<Video>, val continuation: String?)

interface ChannelRepository {
    suspend fun getChannel(channelUrl: String): Result<Channel>

    /** A page of the channel's Videos / Live / Shorts tab: first page when [next] is null. */
    suspend fun tabVideos(channel: Channel, tab: ChannelTabKind, next: Any? = null): Result<ListPage<Video>>

    /** The channel's Playlists tab: first page when [next] is null. */
    suspend fun playlists(channel: Channel, next: Any? = null): Result<ListPage<ChannelPlaylist>>

    /** A YouTube playlist's header and first page of videos. */
    suspend fun getPlaylist(url: String): Result<RemotePlaylist>

    suspend fun morePlaylistVideos(url: String, next: Any): Result<ListPage<Video>>

    /** Videos from [channel] matching [query]; pass the previous page's continuation for more. */
    suspend fun searchChannel(channel: Channel, query: String, continuation: String? = null): Result<ChannelSearchPage>
}
