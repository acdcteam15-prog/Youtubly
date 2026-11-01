package com.youtubly.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One recent search query. The query text itself is the primary key so re-searching the
 *  same thing bumps it to the top instead of creating a duplicate entry. */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long
)
