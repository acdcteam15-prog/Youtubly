package com.youtubly.app.domain.model

data class UserProfile(
    val channelId: String,
    val name: String,
    val handle: String?,
    val avatarUrl: String?,
    val subscriberCount: Long?
)

data class SubscribedChannel(
    val channelId: String,
    val name: String,
    val avatarUrl: String?
) {
    val url: String get() = "https://www.youtube.com/channel/$channelId"
}
