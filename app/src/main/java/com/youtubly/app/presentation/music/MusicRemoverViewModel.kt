package com.youtubly.app.presentation.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.blur.ChannelRef
import com.youtubly.app.blur.SubscriptionsProvider
import com.youtubly.app.music.Benchmark
import com.youtubly.app.music.MusicModels
import com.youtubly.app.music.MusicRemover
import com.youtubly.app.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MusicRemoverViewModel @Inject constructor(
    private val engine: MusicRemover,
    private val player: PlayerController,
) : ViewModel() {
    private val store = engine.store
    val settings = engine.settings
    val status = engine.status
    val debug = engine.debug
    val previewOriginal = engine.previewOriginal
    val models = MusicModels.all
    val benchmarks: StateFlow<Map<String, Benchmark>> = store.benchmarks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val abLoop = player.abLoop

    /** Model id being timed right now, or null. */
    private val _testing = MutableStateFlow<String?>(null)
    val testing: StateFlow<String?> = _testing
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val message: SharedFlow<String> = _message

    val hasVideo: Boolean get() = player.currentVideoId != null

    fun setEnabled(v: Boolean) = viewModelScope.launch {
        store.setEnabled(v)
        if (v && player.currentVideoId != null) _message.tryEmit("Starts within a few seconds")
    }
    fun setModel(id: String) = viewModelScope.launch { store.setModel(id) }
    fun setMusicLevel(v: Int) = viewModelScope.launch { store.setMusicLevel(v) }
    fun setStrength(v: Int) = viewModelScope.launch { store.setStrength(v) }

    fun runSpeedTest() {
        if (_testing.value != null) return
        viewModelScope.launch {
            try {
                for (m in models) {
                    _testing.value = m.id
                    runCatching { engine.benchmark(m) }.onFailure { _message.tryEmit("${m.title}: test failed (${it.message})") }
                }
                val best = benchmarks.value.values.filter { it.verdict == Benchmark.Verdict.GOOD }
                    .maxByOrNull { b -> models.indexOfFirst { it.id == b.modelId } }
                _message.tryEmit(best?.let { "Best model that keeps up comfortably: ${MusicModels.byId(it.modelId).title}" }
                    ?: "None of the models keeps up comfortably on this phone")
            } finally { _testing.value = null }
        }
    }

    fun startPreview() {
        if (!engine.settings.value.enabled) { _message.tryEmit("Turn the music remover on first"); return }
        if (!player.startMusicPreview()) _message.tryEmit("Play a video first, then come back here")
        else _message.tryEmit("Looping 20 s. Switch between Processed and Original")
    }
    fun stopPreview() { player.clearLoop(); engine.previewOriginal.value = false }
    fun setPreviewOriginal(v: Boolean) { engine.previewOriginal.value = v }

    override fun onCleared() { engine.previewOriginal.value = false }
}

@HiltViewModel
class MusicWhitelistViewModel @Inject constructor(engine: MusicRemover, subs: SubscriptionsProvider) : ViewModel() {
    private val store = engine.store
    val query = MutableStateFlow("")
    val excluded: StateFlow<List<ChannelRef>> = combine(store.whitelistChannels, query) { list, q ->
        if (q.isBlank()) list else list.filter { it.name.contains(q, true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val suggestions: StateFlow<List<ChannelRef>> = combine(subs.subscriptions, store.whitelistChannels, query) { s, wl, q ->
        val ids = wl.map { it.id }.toSet()
        s.filter { it.id !in ids && (q.isBlank() || it.name.contains(q, true)) }.sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun add(ch: ChannelRef) = viewModelScope.launch { store.addToWhitelist(ch) }
    fun remove(ch: ChannelRef) = viewModelScope.launch { store.removeFromWhitelist(ch.id) }
    fun clearAll() = viewModelScope.launch { store.clearWhitelist() }
}
