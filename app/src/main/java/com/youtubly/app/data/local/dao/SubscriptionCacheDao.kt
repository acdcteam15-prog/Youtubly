package com.youtubly.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.youtubly.app.data.local.entity.SubscriptionEntity

/**
 * Last known list of subscribed channels (id + name). Lets Search match your channels
 * instantly and offline, without a network round-trip per keystroke.
 */
@Dao
abstract class SubscriptionCacheDao {
    @Query("SELECT * FROM subscriptions_cache")
    abstract suspend fun getAll(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entities: List<SubscriptionEntity>)

    @Query("DELETE FROM subscriptions_cache")
    abstract suspend fun clear()

    @Query("DELETE FROM subscriptions_cache WHERE channelId = :channelId")
    abstract suspend fun delete(channelId: String)

    /** Swap in a fresh list so unsubscribed channels don't linger. */
    @Transaction
    open suspend fun replaceAll(entities: List<SubscriptionEntity>) {
        clear()
        upsertAll(entities)
    }
}
