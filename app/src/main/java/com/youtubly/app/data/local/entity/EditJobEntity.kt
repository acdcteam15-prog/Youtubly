package com.youtubly.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One export in the video editor queue. The column list must stay in sync with
 * YoutublyDatabase.MIGRATION_6_7 (no default values on purpose, so Room's schema check has
 * nothing to disagree about).
 */
@Entity(tableName = "edit_jobs")
data class EditJobEntity(
    @PrimaryKey val id: String,
    val sourceDownloadId: String,
    val sourcePath: String,
    val title: String,
    val thumbnailUrl: String?,
    val sourceDurationMs: Long,
    val startMs: Long,
    val endMs: Long,
    /** EditJobStatus name. */
    val status: String,
    val progress: Int,
    val outputUri: String?,
    val outputName: String?,
    val error: String?,
    val createdAt: Long,
    val finishedAt: Long?,
    /** v8: full ExportSpec (ExportSpecCodec). null on v7 rows = plain trim of startMs..endMs. */
    val spec: String? = null
)
