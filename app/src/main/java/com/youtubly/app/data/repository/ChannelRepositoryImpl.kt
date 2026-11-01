package com.youtubly.app.data.repository

import com.youtubly.app.core.DispatcherProvider
import com.youtubly.app.core.safeIoCall
import com.youtubly.app.data.remote.innertube.InnertubeClient
import com.youtubly.app.data.remote.innertube.InnertubeParser
import com.youtubly.app.data.remote.newpipe.ChannelExtractorService
import com.youtubly.app.domain.model.Channel
import com.youtubly.app.domain.model.ChannelPlaylist
import com.youtubly.app.domain.model.ChannelTabKind
import com.youtubly.app.domain.model.ListPage
import com.youtubly.app.domain.model.RemotePlaylist
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.ChannelRepository
import com.youtubly.app.domain.repository.ChannelSearchPage
import javax.inject.Inject

class ChannelRepositoryImpl @Inject constructor(
    private val channelExtractorService: ChannelExtractorService,
    private val innertubeClient: InnertubeClient,
    private val dispatchers: DispatcherProvider
) : ChannelRepository {
    override suspend fun getChannel(channelUrl: String): Result<Channel> =
        safeIoCall(dispatchers) { channelExtractorService.getChannel(channelUrl) }

    override suspend fun tabVideos(channel: Channel, tab: ChannelTabKind, next: Any?): Result<ListPage<Video>> =
        safeIoCall(dispatchers) { channelExtractorService.tabVideos(channel.url, tab, next) }

    override suspend fun playlists(channel: Channel, next: Any?): Result<ListPage<ChannelPlaylist>> =
        safeIoCall(dispatchers) { channelExtractorService.playlists(channel.url, next) }

    override suspend fun getPlaylist(url: String): Result<RemotePlaylist> =
        safeIoCall(dispatchers) { channelExtractorService.getPlaylist(url) }

    override suspend fun morePlaylistVideos(url: String, next: Any): Result<ListPage<Video>> =
        safeIoCall(dispatchers) { channelExtractorService.morePlaylistVideos(url, next) }

    override suspend fun searchChannel(channel: Channel, query: String, continuation: String?): Result<ChannelSearchPage> =
        safeIoCall(dispatchers) {
            val root = innertubeClient.searchChannel(channel.id, query.trim(), continuation)
            val page = InnertubeParser.parseFeed(root)
            ChannelSearchPage(onlyThisChannel(page.videos, channel), page.continuation)
        }

    companion object {
        /**
         * Keeps results that belong to [channel]. Results inside a channel's search tab usually
         * carry no owner at all (it's implied), so blank owners are filled in; anything that
         * names a *different* channel (collabs, featured shelves) is dropped.
         */
        fun onlyThisChannel(videos: List<Video>, channel: Channel): List<Video> =
            videos.filter { v ->
                val url = v.uploaderUrl
                when {
                    url.isNullOrBlank() -> v.uploaderName.isBlank() || v.uploaderName == channel.name
                    else -> url.contains(channel.id) || url.trimEnd('/') == channel.url.trimEnd('/')
                }
            }.map { v ->
                v.copy(
                    uploaderName = v.uploaderName.ifBlank { channel.name },
                    uploaderUrl = v.uploaderUrl?.takeIf { it.isNotBlank() } ?: channel.url,
                    uploaderAvatarUrl = v.uploaderAvatarUrl ?: channel.avatarUrl
                )
            }
    }
}
