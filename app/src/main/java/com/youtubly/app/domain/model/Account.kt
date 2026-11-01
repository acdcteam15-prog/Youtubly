package com.youtubly.app.domain.model

/** One page of a signed-in feed; pass [continuation] back to load the next page. */
data class FeedPage(val videos: List<Video>, val continuation: String?)

/** Your relationship to a video, from YouTube's watch page. */
data class VideoAccountState(
    val liked: Boolean,
    val subscribed: Boolean?,
    val channelId: String?
)

enum class AccountFeed(val browseId: String) {
    HOME("FEwhat_to_watch"),
    SUBSCRIPTIONS("FEsubscriptions"),
    WATCH_LATER("VLWL"),
    LIKED("VLLL")
}
