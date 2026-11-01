package com.youtubly.app.data.repository

import com.youtubly.app.data.local.dao.DownloadDao
import com.youtubly.app.data.local.dao.PlaylistDao
import com.youtubly.app.data.local.dao.WatchHistoryDao
import com.youtubly.app.data.local.entity.PlaylistFinishedItemEntity
import com.youtubly.app.data.local.entity.PlaylistEntity
import com.youtubly.app.data.local.entity.PlaylistItemEntity
import com.youtubly.app.data.local.entity.PlaylistSummary
import com.youtubly.app.domain.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Local playlists (Room). Everything is on-device; nothing is sent to YouTube. */
@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    private val watchHistoryDao: WatchHistoryDao,
    private val downloadDao: DownloadDao,
    private val prefs: com.youtubly.app.data.local.datastore.UserPreferences
) {
    private var lastRemembered = 0L

    val playlists: Flow<List<PlaylistSummary>> = dao.observeSummaries()

    fun playlist(id: Long): Flow<PlaylistEntity?> = dao.observePlaylist(id)
    fun videos(id: Long): Flow<List<Video>> = dao.observeItems(id).map { rows -> rows.map { it.toVideo() } }
    suspend fun videosOnce(id: Long): List<Video> = dao.items(id).map { it.toVideo() }

    /** Ids of the playlists that already contain [videoId] — drives the sheet's checkboxes. */
    fun playlistsContaining(videoId: String): Flow<Set<Long>> =
        dao.observePlaylistIdsContaining(videoId).map { it.toSet() }

    suspend fun create(name: String, firstVideo: Video? = null, autoRemoveFinished: Boolean = false): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertPlaylist(
            PlaylistEntity(name = name.trim().ifBlank { "Untitled playlist" }, createdAt = now, updatedAt = now, autoRemoveFinished = autoRemoveFinished)
        )
        firstVideo?.let { add(id, it) }
        return id
    }

    /** A new playlist holding all of [videos] (e.g. a copied YouTube playlist). */
    suspend fun saveAll(name: String, videos: List<Video>, autoRemoveFinished: Boolean): Long {
        val id = create(name, autoRemoveFinished = autoRemoveFinished)
        videos.forEach { add(id, it) }
        return id
    }

    // ---------------- auto-remove finished videos / Finished shelf ----------------

    /** Videos already finished and moved out of [id]'s list. */
    fun finishedVideos(id: Long): Flow<List<Video>> = dao.observeFinishedItems(id).map { rows -> rows.map { it.toVideo() } }

    suspend fun setAutoRemove(id: Long, on: Boolean) = dao.setAutoRemove(id, on, System.currentTimeMillis())

    /** (playlistId, videoId) of every video still in an auto-remove playlist — what gets checked against watch progress. */
    fun autoRemoveCandidates(): Flow<List<Pair<Long, String>>> =
        dao.observeAutoRemoveItems().map { rows -> rows.map { it.playlistId to it.videoId } }

    suspend fun finish(playlistId: Long, videoIds: Set<String>) = dao.finishItems(playlistId, videoIds, System.currentTimeMillis())

    /**
     * Restart: every finished video returns to its place, the playlist leaves the Finished shelf, and the videos'
     * watch progress is cleared first (otherwise they'd count as finished again straight away).
     */
    suspend fun restart(id: Long) {
        forgetProgress(dao.finishedItems(id).map { it.videoId })
        dao.restoreAll(id, System.currentTimeMillis())
    }

    /** Brings one finished video back and marks it unwatched. */
    suspend fun restoreVideo(id: Long, videoId: String) {
        forgetProgress(listOf(videoId))
        dao.restoreOne(id, videoId, System.currentTimeMillis())
    }

    private suspend fun forgetProgress(videoIds: List<String>) {
        videoIds.forEach { watchHistoryDao.updatePositionOnly(it, 0) }
        videoIds.chunked(500).forEach { downloadDao.resetWatched(it) }
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim().ifBlank { "Untitled playlist" }, System.currentTimeMillis())
    suspend fun delete(id: Long) = dao.deletePlaylist(id)
    suspend fun add(playlistId: Long, video: Video) {
        dao.append(video.toItem(playlistId), System.currentTimeMillis())
        if (lastRemembered != playlistId) {
            lastRemembered = playlistId
            runCatching { prefs.setLastPlaylistId(playlistId) }
        }
    }
    suspend fun remove(playlistId: Long, videoId: String) = dao.remove(playlistId, videoId, System.currentTimeMillis())
    suspend fun reorder(playlistId: Long, orderedVideoIds: List<String>) =
        dao.reorder(playlistId, orderedVideoIds, System.currentTimeMillis())

    suspend fun setMembership(playlistId: Long, video: Video, member: Boolean) =
        if (member) add(playlistId, video) else remove(playlistId, video.id)

    // ---------------- export / import (JSON) ----------------

    /** All playlists with their items, in order. */
    suspend fun exportJson(): String {
        val root = org.json.JSONObject().put("format", "youtubly-playlists").put("version", 1)
        val arr = org.json.JSONArray()
        dao.allPlaylists().forEach { p ->
            val items = org.json.JSONArray()
            dao.items(p.id).forEach { i ->
                items.put(
                    org.json.JSONObject()
                        .put("videoId", i.videoId).put("url", i.url).put("title", i.title)
                        .put("channel", i.uploaderName).put("channelUrl", i.uploaderUrl ?: "")
                        .put("thumbnail", i.thumbnailUrl ?: "").put("duration", i.durationSec)
                )
            }
            val finished = org.json.JSONArray()
            dao.finishedItems(p.id).forEach { f ->
                finished.put(
                    org.json.JSONObject()
                        .put("videoId", f.videoId).put("url", f.url).put("title", f.title)
                        .put("channel", f.uploaderName).put("channelUrl", f.uploaderUrl ?: "")
                        .put("thumbnail", f.thumbnailUrl ?: "").put("duration", f.durationSec)
                        .put("position", f.position).put("finishedAt", f.finishedAt)
                )
            }
            arr.put(
                org.json.JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("items", items)
                    .put("autoRemoveFinished", p.autoRemoveFinished).put("finishedAt", p.finishedAt ?: 0L)
                    .put("finishedItems", finished)
            )
        }
        return root.put("playlists", arr).toString(2)
    }

    /** Adds every playlist in [json] as a new playlist (never merges or overwrites). Returns how many. */
    suspend fun importJson(json: String): Int {
        val root = org.json.JSONObject(json)
        val arr = root.getJSONArray("playlists")
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = create(o.optString("name", "Imported playlist"), autoRemoveFinished = o.optBoolean("autoRemoveFinished", false))
            val items = o.optJSONArray("items") ?: org.json.JSONArray()
            for (j in 0 until items.length()) {
                val it = items.getJSONObject(j)
                val vid = it.optString("videoId")
                if (vid.isBlank()) continue
                add(
                    id,
                    Video(
                        id = vid,
                        url = it.optString("url").ifBlank { "https://www.youtube.com/watch?v=$vid" },
                        title = it.optString("title"),
                        uploaderName = it.optString("channel"),
                        uploaderUrl = it.optString("channelUrl").ifBlank { null },
                        thumbnailUrl = it.optString("thumbnail").ifBlank { null },
                        duration = it.optLong("duration"),
                        viewCount = -1,
                        uploadDate = null
                    )
                )
            }
            // Finished shelf state (files from older versions simply don't have it).
            val finished = o.optJSONArray("finishedItems")
            if (finished != null) {
                for (j in 0 until finished.length()) {
                    val f = finished.getJSONObject(j)
                    val vid = f.optString("videoId")
                    if (vid.isBlank()) continue
                    dao.insertFinished(
                        PlaylistFinishedItemEntity(
                            playlistId = id, videoId = vid, position = f.optInt("position", j),
                            url = f.optString("url").ifBlank { "https://www.youtube.com/watch?v=$vid" },
                            title = f.optString("title"), uploaderName = f.optString("channel"),
                            uploaderUrl = f.optString("channelUrl").ifBlank { null },
                            thumbnailUrl = f.optString("thumbnail").ifBlank { null },
                            durationSec = f.optLong("duration"), addedAt = System.currentTimeMillis(),
                            finishedAt = f.optLong("finishedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
            o.optLong("finishedAt", 0L).takeIf { it > 0 }?.let { dao.setFinishedAt(id, it) }
            count++
        }
        return count
    }
}

private fun PlaylistItemEntity.toVideo() = Video(
    id = videoId, url = url, title = title, uploaderName = uploaderName, uploaderUrl = uploaderUrl,
    thumbnailUrl = thumbnailUrl, duration = durationSec, viewCount = -1, uploadDate = null
)

private fun Video.toItem(playlistId: Long) = PlaylistItemEntity(
    playlistId = playlistId, videoId = id, position = 0, url = url, title = title,
    uploaderName = uploaderName, uploaderUrl = uploaderUrl, thumbnailUrl = thumbnailUrl,
    durationSec = duration, addedAt = System.currentTimeMillis()
)

private fun PlaylistFinishedItemEntity.toVideo() = Video(
    id = videoId, url = url, title = title, uploaderName = uploaderName, uploaderUrl = uploaderUrl,
    thumbnailUrl = thumbnailUrl, duration = durationSec, viewCount = -1, uploadDate = null
)
