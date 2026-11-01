package com.youtubly.app.playback

import android.content.Context
import com.youtubly.app.domain.model.Video
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SavedVideo(
    val id: String, val url: String, val title: String, val uploaderName: String,
    val uploaderUrl: String? = null, val thumbnailUrl: String? = null, val duration: Long = 0,
    val viewCount: Long = 0, val uploadDate: String? = null, val uploaderAvatarUrl: String? = null,
) {
    fun toVideo() = Video(id, url, title, uploaderName, uploaderUrl, thumbnailUrl, duration, viewCount, uploadDate,
        uploaderAvatarUrl = uploaderAvatarUrl)
    companion object {
        fun of(v: Video) = SavedVideo(v.id, v.url, v.title, v.uploaderName, v.uploaderUrl, v.thumbnailUrl,
            v.duration, v.viewCount, v.uploadDate, v.uploaderAvatarUrl)
    }
}

@Serializable
data class SavedQueue(val title: String, val index: Int, val positionMs: Long, val items: List<SavedVideo>, val savedAt: Long) {
    val current: SavedVideo? get() = items.getOrNull(index)
}

/**
 * Saves the playlist queue (and where you were in it) so it survives the app being killed.
 * On the next launch [pending] holds it until the user resumes or dismisses it.
 *
 * A queue the user closed on purpose (player stopped → queue cleared) is deleted, so only an
 * interrupted queue is ever offered back. Saved queues older than [MAX_AGE_MS] are ignored.
 */
@Singleton
class QueueResume @Inject constructor(
    @ApplicationContext context: Context,
    private val controller: PlayerController,
) {
    private val file = File(context.filesDir, "saved_queue.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _pending = MutableStateFlow<SavedQueue?>(null)
    val pending: StateFlow<SavedQueue?> = _pending.asStateFlow()
    private var started = false

    /** Call once from MainActivity. Reads the saved queue *before* watching for changes. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            _pending.value = withContext(Dispatchers.IO) {
                runCatching { json.decodeFromString<SavedQueue>(file.readText()) }.getOrNull()
                    ?.takeIf { System.currentTimeMillis() - it.savedAt < MAX_AGE_MS && it.current != null }
            }
            // drop(1): the first value is the empty queue of a fresh start, not the user closing it.
            launch { controller.queue.drop(1).collect { save() } }
            while (true) { delay(15_000); if (controller.player.isPlaying) save() }
        }
    }

    fun dismiss() { _pending.value = null; scope.launch(Dispatchers.IO) { file.delete() } }

    /** Restores the queue into the player and returns (url, startMs) for the player sheet to open. */
    fun resume(): Pair<String, Long>? {
        val q = _pending.value ?: return null
        val cur = q.current ?: return null
        controller.setQueue(q.title, q.items.map { it.toVideo() }, q.index)
        _pending.value = null
        return cur.url to q.positionMs
    }

    private fun save() {
        val q = controller.queue.value
        if (q == null) { scope.launch(Dispatchers.IO) { file.delete() }; return }
        // Only record a position that belongs to the queue's current item.
        val pos = if (q.current?.id == controller.currentVideoId) controller.player.currentPosition else 0L
        val saved = SavedQueue(q.title, q.index, pos, q.items.map(SavedVideo::of), System.currentTimeMillis())
        scope.launch(Dispatchers.IO) { runCatching { file.writeText(json.encodeToString(saved)) } }
    }

    companion object { const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 }
}
