package com.youtubly.app.data.remote.newpipe

import com.youtubly.app.domain.model.Channel
import com.youtubly.app.domain.model.ChannelPlaylist
import com.youtubly.app.domain.model.ChannelTabKind
import com.youtubly.app.domain.model.ListPage
import com.youtubly.app.domain.model.RemotePlaylist
import com.youtubly.app.domain.model.Video
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Channel pages and playlists. Singleton so the channel's tab handles found by [getChannel] are
 * reused when the Videos / Playlists tabs ask for more pages, instead of re-fetching the channel.
 */
@Singleton
class ChannelExtractorService @Inject constructor(
    private val streamingService: StreamingService
) {
    private val tabHandlers = ConcurrentHashMap<String, ListLinkHandler>()

    private fun key(channelUrl: String, filter: String) = "$channelUrl|$filter"

    private fun filterOf(tab: ChannelTabKind) = when (tab) {
        ChannelTabKind.VIDEOS -> ChannelTabs.VIDEOS
        ChannelTabKind.LIVE -> ChannelTabs.LIVESTREAMS
        ChannelTabKind.SHORTS -> ChannelTabs.SHORTS
        ChannelTabKind.PLAYLISTS -> ChannelTabs.PLAYLISTS
    }

    private fun tabsOf(channelUrl: String): Set<ChannelTabKind> =
        ChannelTabKind.entries.filter { tabHandlers.containsKey(key(channelUrl, filterOf(it))) }.toSet()

    /** Shorts are hidden everywhere else in the app; only the Shorts tab (opt-in) keeps them. */
    private fun List<StreamInfoItem>.toTabVideos(tab: ChannelTabKind): List<Video> =
        if (tab == ChannelTabKind.SHORTS) map { it.toVideo() } else toVideosWithoutShorts()

    private fun remember(channelUrl: String, info: ChannelInfo) {
        info.tabs.forEach { tab -> tab.contentFilters.forEach { f -> tabHandlers[key(channelUrl, f)] = tab } }
    }

    /** The channel's tab for [filter]; refetches the channel once if this one hasn't been seen yet. */
    private fun handlerFor(channelUrl: String, filter: String): ListLinkHandler {
        tabHandlers[key(channelUrl, filter)]?.let { return it }
        remember(channelUrl, ChannelInfo.getInfo(streamingService, channelUrl))
        return tabHandlers[key(channelUrl, filter)] ?: error("This channel has no $filter tab")
    }

    fun getChannel(channelUrl: String): Channel {
        val info = ChannelInfo.getInfo(streamingService, channelUrl)
        remember(info.url, info)

        var videosNext: Any? = null
        val videos = tabHandlers[key(info.url, ChannelTabs.VIDEOS)]?.let { tab ->
            runCatching {
                val tabInfo = ChannelTabInfo.getInfo(streamingService, tab)
                videosNext = tabInfo.nextPage.takeIf { tabInfo.hasNextPage() }
                tabInfo.relatedItems.filterIsInstance<StreamInfoItem>().toVideosWithoutShorts()
            }.getOrDefault(emptyList())
        } ?: emptyList()

        return Channel(
            id = info.id,
            url = info.url,
            name = info.name,
            avatarUrl = info.avatars.maxByOrNull { it.height }?.url,
            bannerUrl = info.banners.maxByOrNull { it.height }?.url,
            subscriberCount = info.subscriberCount,
            description = info.description,
            videos = videos,
            tabs = tabsOf(info.url),
            videosNext = videosNext
        )
    }

    /**
     * A page of the Videos / Live / Shorts tab: the first page when [next] is null, otherwise the page [next]
     * points at. (The Videos tab's first page normally comes with [getChannel].)
     */
    fun tabVideos(channelUrl: String, tab: ChannelTabKind, next: Any?): ListPage<Video> {
        val handler = handlerFor(channelUrl, filterOf(tab))
        return if (next == null) {
            val tabInfo = ChannelTabInfo.getInfo(streamingService, handler)
            ListPage(
                tabInfo.relatedItems.filterIsInstance<StreamInfoItem>().toTabVideos(tab),
                tabInfo.nextPage.takeIf { tabInfo.hasNextPage() }
            )
        } else {
            val page = ChannelTabInfo.getMoreItems(streamingService, handler, next as Page)
            ListPage(page.items.filterIsInstance<StreamInfoItem>().toTabVideos(tab), page.nextPage.takeIf { page.hasNextPage() })
        }
    }

    /** First page when [next] is null, otherwise the page [next] points at. */
    fun playlists(channelUrl: String, next: Any?): ListPage<ChannelPlaylist> {
        val handler = handlerFor(channelUrl, ChannelTabs.PLAYLISTS)
        return if (next == null) {
            val tabInfo = ChannelTabInfo.getInfo(streamingService, handler)
            ListPage(
                tabInfo.relatedItems.filterIsInstance<PlaylistInfoItem>().map { it.toChannelPlaylist() },
                tabInfo.nextPage.takeIf { tabInfo.hasNextPage() }
            )
        } else {
            val page = ChannelTabInfo.getMoreItems(streamingService, handler, next as Page)
            ListPage(
                page.items.filterIsInstance<PlaylistInfoItem>().map { it.toChannelPlaylist() },
                page.nextPage.takeIf { page.hasNextPage() }
            )
        }
    }

    fun getPlaylist(url: String): RemotePlaylist {
        val info = PlaylistInfo.getInfo(streamingService, url)
        return RemotePlaylist(
            url = info.url,
            title = info.name,
            uploaderName = info.uploaderName?.takeIf { it.isNotBlank() } ?: info.subChannelName,
            uploaderUrl = info.uploaderUrl?.takeIf { it.isNotBlank() } ?: info.subChannelUrl,
            thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
            videoCount = info.streamCount,
            first = ListPage(
                info.relatedItems.toVideosWithoutShorts(),
                info.nextPage.takeIf { info.hasNextPage() }
            )
        )
    }

    fun morePlaylistVideos(url: String, next: Any): ListPage<Video> {
        val page = PlaylistInfo.getMoreItems(streamingService, url, next as Page)
        return ListPage(page.items.toVideosWithoutShorts(), page.nextPage.takeIf { page.hasNextPage() })
    }

    private fun PlaylistInfoItem.toChannelPlaylist() = ChannelPlaylist(
        url = url,
        title = name,
        thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
        videoCount = streamCount,
        uploaderName = uploaderName
    )
}
