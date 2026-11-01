package com.youtubly.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.youtubly.app.data.local.entity.PlaylistEntity
import com.youtubly.app.data.local.entity.PlaylistFinishedItemEntity
import com.youtubly.app.data.local.entity.PlaylistItemEntity
import com.youtubly.app.data.local.entity.PlaylistSummary
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, p.createdAt, p.updatedAt,
               (SELECT COUNT(*) FROM playlist_items i WHERE i.playlistId = p.id) AS videoCount,
               COALESCE(
                   (SELECT i.thumbnailUrl FROM playlist_items i WHERE i.playlistId = p.id ORDER BY i.position LIMIT 1),
                   (SELECT f.thumbnailUrl FROM playlist_finished_items f WHERE f.playlistId = p.id ORDER BY f.position LIMIT 1)
               ) AS coverUrl,
               p.autoRemoveFinished AS autoRemoveFinished,
               p.finishedAt AS finishedAt,
               (SELECT COUNT(*) FROM playlist_finished_items f WHERE f.playlistId = p.id) AS finishedCount
        FROM playlists p
        ORDER BY p.updatedAt DESC
        """
    )
    abstract fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    abstract suspend fun allPlaylists(): List<PlaylistEntity>

    /** Search: playlists whose name matches. */
    @Query("SELECT * FROM playlists WHERE name LIKE '%' || :q || '%' ORDER BY updatedAt DESC LIMIT :limit")
    abstract suspend fun searchPlaylists(q: String, limit: Int): List<PlaylistEntity>

    /** Search: saved videos (in any playlist) whose title or channel matches. */
    @Query(
        "SELECT * FROM playlist_items WHERE title LIKE '%' || :q || '%' OR uploaderName LIKE '%' || :q || '%' " +
            "ORDER BY addedAt DESC LIMIT :limit"
    )
    abstract suspend fun searchItems(q: String, limit: Int): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position")
    abstract fun observeItems(id: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position")
    abstract suspend fun items(id: Long): List<PlaylistItemEntity>

    @Query("SELECT playlistId FROM playlist_items WHERE videoId = :videoId")
    abstract fun observePlaylistIdsContaining(videoId: String): Flow<List<Long>>

    @Query("SELECT * FROM playlist_finished_items WHERE playlistId = :id ORDER BY position")
    abstract fun observeFinishedItems(id: Long): Flow<List<PlaylistFinishedItemEntity>>

    @Query("SELECT * FROM playlist_finished_items WHERE playlistId = :id ORDER BY position")
    abstract suspend fun finishedItems(id: Long): List<PlaylistFinishedItemEntity>

    /** Every remaining video of every auto-remove playlist that isn't finished yet — what the auto-finisher watches. */
    @Query(
        "SELECT i.* FROM playlist_items i JOIN playlists p ON p.id = i.playlistId " +
            "WHERE p.autoRemoveFinished = 1 AND p.finishedAt IS NULL"
    )
    abstract fun observeAutoRemoveItems(): Flow<List<PlaylistItemEntity>>

    @Query("UPDATE playlists SET autoRemoveFinished = :on, updatedAt = :now WHERE id = :id")
    abstract suspend fun setAutoRemove(id: Long, on: Boolean, now: Long)

    @Query("UPDATE playlists SET finishedAt = :at WHERE id = :id")
    abstract suspend fun setFinishedAt(id: Long, at: Long?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertFinished(item: PlaylistFinishedItemEntity)

    @Query("DELETE FROM playlist_finished_items WHERE playlistId = :id")
    protected abstract suspend fun deleteFinishedOf(id: Long)

    @Query("DELETE FROM playlist_finished_items WHERE playlistId = :id AND videoId = :videoId")
    protected abstract suspend fun deleteFinished(id: Long, videoId: String)

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :id")
    protected abstract suspend fun countItems(id: Long): Int

    @Insert
    abstract suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    abstract suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    protected abstract suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id")
    protected abstract suspend fun deleteItemsOf(id: Long)

    @Query("SELECT MAX(position) FROM playlist_items WHERE playlistId = :id")
    protected abstract suspend fun maxPosition(id: Long): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertItem(item: PlaylistItemEntity): Long

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND videoId = :videoId")
    protected abstract suspend fun deleteItem(playlistId: Long, videoId: String)

    @Query("UPDATE playlist_items SET position = :position WHERE playlistId = :playlistId AND videoId = :videoId")
    protected abstract suspend fun setPosition(playlistId: Long, videoId: String, position: Int)

    /** No foreign key (keeps the migration trivial), so items are removed explicitly. */
    @Transaction
    open suspend fun deletePlaylist(id: Long) {
        deleteItemsOf(id)
        deleteFinishedOf(id)
        deletePlaylistRow(id)
    }

    /** Appends at the end; a video already in the list is left where it is. */
    @Transaction
    open suspend fun append(item: PlaylistItemEntity, now: Long) {
        val pos = (maxPosition(item.playlistId) ?: -1) + 1
        if (insertItem(item.copy(position = pos)) != -1L) {
            // A new video on a playlist that was on the Finished shelf: it has something to watch again.
            setFinishedAt(item.playlistId, null)
            touch(item.playlistId, now)
        }
    }

    @Transaction
    open suspend fun remove(playlistId: Long, videoId: String, now: Long) {
        deleteItem(playlistId, videoId)
        // Close the gap so positions stay 0..n-1.
        items(playlistId).forEachIndexed { i, it -> if (it.position != i) setPosition(playlistId, it.videoId, i) }
        touch(playlistId, now)
    }

    /** [orderedVideoIds] is the full new order; ids not in the list are ignored. */
    @Transaction
    open suspend fun reorder(playlistId: Long, orderedVideoIds: List<String>, now: Long) {
        orderedVideoIds.forEachIndexed { i, vid -> setPosition(playlistId, vid, i) }
        touch(playlistId, now)
    }

    /**
     * Moves [videoIds] out of the list into the finished table. Positions are left as they are (gaps are harmless
     * for ordering) so every video keeps its original place for a later restore. When the last video leaves, the
     * playlist goes on the Finished shelf.
     */
    @Transaction
    open suspend fun finishItems(playlistId: Long, videoIds: Set<String>, now: Long) {
        val moving = items(playlistId).filter { it.videoId in videoIds }
        if (moving.isEmpty()) return
        moving.forEach {
            insertFinished(
                PlaylistFinishedItemEntity(
                    it.playlistId, it.videoId, it.position, it.url, it.title, it.uploaderName,
                    it.uploaderUrl, it.thumbnailUrl, it.durationSec, it.addedAt, now
                )
            )
            deleteItem(playlistId, it.videoId)
        }
        if (countItems(playlistId) == 0) setFinishedAt(playlistId, now)
        touch(playlistId, now)
    }

    /** Puts every finished video back in its original place and takes the playlist off the Finished shelf. */
    @Transaction
    open suspend fun restoreAll(playlistId: Long, now: Long) {
        val finished = finishedItems(playlistId)
        val active = items(playlistId)
        // Merge by position (finished first on a tie), then number 0..n-1 again.
        val merged = (finished.map { Triple(it.position, 0, it.toItem()) } + active.map { Triple(it.position, 1, it) })
            .sortedWith(compareBy<Triple<Int, Int, PlaylistItemEntity>>({ it.first }, { it.second }))
        deleteItemsOf(playlistId)
        deleteFinishedOf(playlistId)
        merged.forEachIndexed { i, (_, _, item) -> insertItem(item.copy(position = i)) }
        setFinishedAt(playlistId, null)
        touch(playlistId, now)
    }

    /** Brings one finished video back (at its old place when free, else at the end). */
    @Transaction
    open suspend fun restoreOne(playlistId: Long, videoId: String, now: Long) {
        val f = finishedItems(playlistId).firstOrNull { it.videoId == videoId } ?: return
        val taken = items(playlistId).any { it.position == f.position }
        val pos = if (taken) (maxPosition(playlistId) ?: -1) + 1 else f.position
        insertItem(f.toItem().copy(position = pos))
        deleteFinished(playlistId, videoId)
        setFinishedAt(playlistId, null)
        touch(playlistId, now)
    }
}

private fun PlaylistFinishedItemEntity.toItem() = PlaylistItemEntity(
    playlistId, videoId, position, url, title, uploaderName, uploaderUrl, thumbnailUrl, durationSec, addedAt
)
