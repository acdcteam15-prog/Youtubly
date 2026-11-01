package com.youtubly.app.data.remote.rydb

import kotlinx.serialization.Serializable

@Serializable
data class DislikeResponseDto(
    val id: String = "",
    val dislikes: Long = 0,
    val likes: Long = 0,
    val rating: Double = 0.0,
    val viewCount: Long = 0,
    val deleted: Boolean = false
)
