package com.youtubly.app.domain.repository

import com.youtubly.app.domain.model.AccountFeed
import com.youtubly.app.domain.model.FeedPage
import com.youtubly.app.domain.model.SubscribedChannel
import com.youtubly.app.domain.model.UserProfile
import com.youtubly.app.domain.model.VideoAccountState

/** Everything that needs your signed-in YouTube session. */
interface AccountRepository {
    suspend fun feed(feed: AccountFeed, continuation: String? = null): Result<FeedPage>
    suspend fun profile(): Result<UserProfile?>
    suspend fun subscribedChannels(): Result<List<SubscribedChannel>>
    suspend fun videoState(videoId: String): Result<VideoAccountState>
    suspend fun setLiked(videoId: String, liked: Boolean): Result<Unit>
    suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit>
    suspend fun setWatchLater(videoId: String, saved: Boolean): Result<Unit>
    /** Replays a "Not interested" / "Don't recommend channel" feedback token from a video's menu. */
    suspend fun sendFeedback(token: String): Result<Unit>
}
