package com.youtubly.app.presentation.playlists

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.data.local.entity.PlaylistEntity
import com.youtubly.app.data.local.entity.PlaylistSummary
import com.youtubly.app.data.repository.PlaylistRepository
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.playlist.PlaylistOrder
import com.youtubly.app.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repo: PlaylistRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    /** null until the first database read, so the screen can tell "loading" from "empty". */
    val playlists: StateFlow<List<PlaylistSummary>?> = repo.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages

    fun create(name: String, autoRemoveFinished: Boolean = false) {
        viewModelScope.launch { repo.create(name, autoRemoveFinished = autoRemoveFinished) }
    }

    fun rename(id: Long, name: String) { viewModelScope.launch { repo.rename(id, name) } }

    fun delete(id: Long, name: String) {
        viewModelScope.launch { repo.delete(id); _messages.emit("Deleted \"$name\"") }
    }

    /** Removes every playlist on the Finished shelf. */
    fun clearFinished(ids: List<Long>) {
        viewModelScope.launch {
            ids.forEach { repo.delete(it) }
            _messages.emit(if (ids.size == 1) "Cleared 1 finished playlist" else "Cleared ${ids.size} finished playlists")
        }
    }

    fun setAutoRemove(id: Long, on: Boolean) {
        viewModelScope.launch {
            repo.setAutoRemove(id, on)
            _messages.emit(if (on) "Watched videos will leave this playlist" else "Videos stay in this playlist after you watch them")
        }
    }

    /** Finished shelf → back to a normal playlist with every video restored and marked unwatched. */
    fun restart(id: Long) {
        viewModelScope.launch { repo.restart(id); _messages.emit("Playlist restarted") }
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            val r = runCatching {
                val json = repo.exportJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                }
            }
            _messages.emit(if (r.isSuccess) "Playlists exported" else "Export failed: ${r.exceptionOrNull()?.message}")
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val r = runCatching {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                }
                repo.importJson(json)
            }
            _messages.emit(
                r.fold(
                    onSuccess = { n -> if (n == 1) "Imported 1 playlist" else "Imported $n playlists" },
                    onFailure = { "Not a Youtubly playlist file" }
                )
            )
        }
    }
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: PlaylistRepository,
    private val playerController: PlayerController
) : ViewModel() {
    val playlistId: Long = savedStateHandle.get<Long>("playlistId") ?: -1L

    val playlist: StateFlow<PlaylistEntity?> = repo.playlist(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val videos: StateFlow<List<Video>?> = repo.videos(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Videos already finished and moved out of this list (auto-remove playlists). */
    val finished: StateFlow<List<Video>> = repo.finishedVideos(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setAutoRemove(on: Boolean) { viewModelScope.launch { repo.setAutoRemove(playlistId, on) } }
    fun restart() { viewModelScope.launch { repo.restart(playlistId) } }
    fun restore(video: Video) { viewModelScope.launch { repo.restoreVideo(playlistId, video.id) } }

    fun rename(name: String) { viewModelScope.launch { repo.rename(playlistId, name) } }
    fun delete(done: () -> Unit) { viewModelScope.launch { repo.delete(playlistId); done() } }
    fun remove(video: Video) { viewModelScope.launch { repo.remove(playlistId, video.id) } }
    fun undoRemove(video: Video, index: Int) {
        viewModelScope.launch {
            repo.add(playlistId, video)
            // add() appends; put it back where it was.
            val ids = repo.videosOnce(playlistId).map { it.id }
            repo.reorder(playlistId, PlaylistOrder.move(ids, ids.lastIndex, index.coerceIn(0, ids.lastIndex)))
        }
    }
    fun reorder(orderedIds: List<String>) { viewModelScope.launch { repo.reorder(playlistId, orderedIds) } }

    /**
     * Sets up the player queue and returns the video to open (or null if the list is empty).
     * [startAt] = tapped video; with [shuffle] it plays first and the rest are shuffled.
     */
    fun startQueue(shuffle: Boolean, startAt: Video? = null): Video? {
        // A playlist on the Finished shelf has no remaining videos: playing it replays the finished ones.
        val list = videos.value.orEmpty().ifEmpty { finished.value }
        if (list.isEmpty()) return null
        val name = playlist.value?.name ?: "Playlist"
        val ordered = if (shuffle) PlaylistOrder.shuffled(list, startAt) else list
        val index = if (shuffle) 0 else startAt?.let { s -> list.indexOfFirst { it.id == s.id } }?.coerceAtLeast(0) ?: 0
        playerController.setQueue(if (shuffle) "$name · shuffled" else name, ordered, index)
        return ordered[index]
    }
}
