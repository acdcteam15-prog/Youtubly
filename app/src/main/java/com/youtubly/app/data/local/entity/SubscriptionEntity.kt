package com.youtubly.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions_cache")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val channelName: String,
    val cachedAt: Long
)
