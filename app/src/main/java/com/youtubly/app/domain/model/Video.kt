package com.youtubly.app.domain.model

data class Video(
    val id: String,
    val url: String,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val duration: Long,
    val viewCount: Long,
    val uploadDate: String?,
    val isLive: Boolean = false,
    /** Channel avatar, when the source provides one (falls back to a letter badge). */
    val uploaderAvatarUrl: String? = null,
    /** Opaque feedback tokens from YouTube's menu, used to remove this video from Home/Subs. */
    val notInterestedToken: String? = null,
    val dontRecommendChannelToken: String? = null
)
