package com.youtubly.app.data.repository

import com.youtubly.app.core.DispatcherProvider
import com.youtubly.app.core.safeIoCall
import com.youtubly.app.data.remote.innertube.InnertubeClient
import com.youtubly.app.data.remote.innertube.InnertubeParser
import com.youtubly.app.domain.model.AccountFeed
import com.youtubly.app.domain.model.FeedPage
import com.youtubly.app.domain.model.SubscribedChannel
import com.youtubly.app.domain.model.UserProfile
import com.youtubly.app.domain.model.VideoAccountState
import com.youtubly.app.domain.repository.AccountRepository
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import javax.inject.Inject

class AccountRepositoryImpl @Inject constructor(
    private val client: InnertubeClient,
    private val dispatchers: DispatcherProvider
) : AccountRepository {

    override suspend fun feed(feed: AccountFeed, continuation: String?): Result<FeedPage> =
        safeIoCall(dispatchers) {
            val page = InnertubeParser.parseFeed(
                if (continuation != null) client.browse(continuation = continuation)
                else client.browse(browseId = feed.browseId)
            )
            FeedPage(page.videos, page.continuation)
        }

    override suspend fun profile(): Result<UserProfile?> = safeIoCall(dispatchers) {
        InnertubeParser.parseProfile(client.post("account/account_menu") {})
    }

    override suspend fun subscribedChannels(): Result<List<SubscribedChannel>> = safeIoCall(dispatchers) {
        val all = LinkedHashMap<String, SubscribedChannel>()
        var (channels, token) = InnertubeParser.parseChannels(client.browse(browseId = "FEchannels"))
        channels.forEach { all.putIfAbsent(it.channelId, it) }
        var pages = 0
        while (token != null && pages++ < 5) {
            val next = InnertubeParser.parseChannels(client.browse(continuation = token))
            next.first.forEach { all.putIfAbsent(it.channelId, it) }
            token = next.second
        }
        all.values.toList()
    }

    override suspend fun videoState(videoId: String): Result<VideoAccountState> = safeIoCall(dispatchers) {
        val next = client.post("next") { put("videoId", videoId) }
        VideoAccountState(
            liked = InnertubeParser.parseLikeStatus(next),
            subscribed = InnertubeParser.parseSubscribed(next),
            channelId = InnertubeParser.parseChannelId(next)
        )
    }

    override suspend fun setLiked(videoId: String, liked: Boolean): Result<Unit> = safeIoCall(dispatchers) {
        client.post(if (liked) "like/like" else "like/removelike") {
            putJsonObject("target") { put("videoId", videoId) }
        }
        Unit
    }

    override suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> = safeIoCall(dispatchers) {
        client.post(if (subscribed) "subscription/subscribe" else "subscription/unsubscribe") {
            putJsonArray("channelIds") { add(JsonPrimitive(channelId)) }
        }
        Unit
    }

    override suspend fun setWatchLater(videoId: String, saved: Boolean): Result<Unit> = safeIoCall(dispatchers) {
        client.post("browse/edit_playlist") {
            put("playlistId", "WL")
            putJsonArray("actions") {
                addJsonObject {
                    if (saved) {
                        put("addedVideoId", videoId)
                        put("action", "ACTION_ADD_VIDEO")
                    } else {
                        put("removedVideoId", videoId)
                        put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                    }
                }
            }
        }
        Unit
    }

    override suspend fun sendFeedback(token: String): Result<Unit> = safeIoCall(dispatchers) {
        client.post("feedback") {
            putJsonArray("feedbackTokens") { add(JsonPrimitive(token)) }
            put("isFeedbackTokenUnencrypted", false)
            put("shouldMerge", false)
        }
        Unit
    }
}
