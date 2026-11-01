package com.youtubly.app.presentation.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadStatus
import com.youtubly.app.domain.repository.DownloadRepository
import com.youtubly.app.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Audio-only downloads, or every finished download played as audio. */
enum class AudioSource(val label: String) { AUDIO("Audio files"), ALL("All downloads") }

/** Position/duration for the now-playing bar, sampled while the screen is open. */
data class AudioProgress(val positionMs: Long = 0, val durationMs: Long = 0, val playing: Boolean = false, val shuffle: Boolean = false)

@HiltViewModel
class AudioLibraryViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    val playerController: PlayerController
) : ViewModel() {

    private val _source = MutableStateFlow(AudioSource.AUDIO)
    val source: StateFlow<AudioSource> = _source

    /** Finished downloads with a file, newest first, for the chosen source. */
    val tracks: StateFlow<List<DownloadItem>?> = combine(downloadRepository.observeDownloads(), _source) { list, src ->
        list.filter { it.status == DownloadStatus.COMPLETED && it.filePath != null && (src == AudioSource.ALL || it.isAudioOnly) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val nowPlaying: StateFlow<PlayerController.AudioNowPlaying?> = playerController.audioNowPlaying

    private val _progress = MutableStateFlow(AudioProgress())
    val progress: StateFlow<AudioProgress> = _progress

    init {
        // Light polling (4×/s) only while this screen's view model is alive.
        viewModelScope.launch {
            val p = playerController.player
            while (isActive) {
                _progress.value = AudioProgress(p.currentPosition, p.duration.coerceAtLeast(0), p.isPlaying, p.shuffleModeEnabled)
                delay(250)
            }
        }
    }

    fun setSource(s: AudioSource) { _source.value = s }

    fun play(startId: String? = null, shuffle: Boolean = false) {
        val list = tracks.value.orEmpty()
        if (list.isEmpty()) return
        val start = if (shuffle && startId == null) list.indices.random() else list.indexOfFirst { it.id == startId }.coerceAtLeast(0)
        viewModelScope.launch {
            // Old downloads may not have their channel stored yet. Resolve the first track briefly
            // (like the single-video player does) so its Excluded-channel status is right from the
            // first second; the rest are filled in the background for next time.
            val first = list[start]
            val firstChannel = first.uploaderUrl
                ?: kotlinx.coroutines.withTimeoutOrNull(3_000) { downloadRepository.channelUrlFor(first.id) }
            playerController.playLocalPlaylist(
                list.mapIndexed { i, it ->
                    PlayerController.LocalTrack(it.id, it.videoId, it.title, it.filePath!!, it.thumbnailUrl,
                        if (i == start) firstChannel else it.uploaderUrl,
                        resumeAtMs = if (i == start && !shuffle) it.resumeAtMs else null)
                },
                startIndex = start,
                shuffle = shuffle
            )
            list.filter { it.uploaderUrl == null && it.id != first.id }.take(25).forEach {
                kotlinx.coroutines.withTimeoutOrNull(5_000) { downloadRepository.channelUrlFor(it.id) }
            }
        }
    }

    fun togglePlay() { playerController.player.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { playerController.player.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun previous() {
        // Like music apps: more than 3 s in → restart the track; otherwise go back one.
        playerController.player.let { if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() }
    }
    fun seekTo(fraction: Float) {
        val d = playerController.player.duration
        if (d > 0) playerController.player.seekTo((d * fraction).toLong())
    }
    fun toggleShuffle() { playerController.setAudioShuffle(!playerController.player.shuffleModeEnabled) }
}
