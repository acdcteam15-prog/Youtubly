package com.youtubly.app.presentation.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.blur.BlurPreferencesStore
import com.youtubly.app.blur.BodyCoverage
import com.youtubly.app.blur.ChannelBlurOverride
import com.youtubly.app.blur.ChannelRef
import com.youtubly.app.blur.GenderRule
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.domain.playback.ChannelDefaults
import com.youtubly.app.music.MusicPreferencesStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything that can be set for ONE channel, gathered from the places each setting actually lives. */
@HiltViewModel
class ChannelSettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val blurStore: BlurPreferencesStore,
    private val musicStore: MusicPreferencesStore
) : ViewModel() {

    data class Ui(
        val bound: Boolean = false,
        val speed: Float? = null,
        val d: ChannelDefaults = ChannelDefaults(),
        val blur: ChannelBlurOverride = ChannelBlurOverride(),
        val blurNever: Boolean = false,
        val musicNever: Boolean = false,
        val musicAlways: Boolean = false
    )

    private val channel = MutableStateFlow<ChannelRef?>(null)
    fun bind(ref: ChannelRef) { channel.value = ref }

    val ui: StateFlow<Ui> = combine(channel, prefs.channelDefaults, prefs.channelSpeeds, blurStore.settings, musicStore.settings) { ref, defaults, speeds, blur, music ->
        if (ref == null) Ui() else Ui(
            bound = true,
            speed = ChannelDefaults.lookup(speeds, ref.id),
            d = ChannelDefaults.lookup(defaults, ref.id) ?: ChannelDefaults(),
            blur = blur.channelOverrides[ref.id] ?: ChannelBlurOverride(),
            blurNever = ref.id in blur.whitelist,
            musicNever = ref.id in music.whitelist,
            musicAlways = ref.id in music.alwaysChannels
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Ui())

    // ---- playback / sound / feed: one saved record per channel ----
    private fun update(change: (ChannelDefaults) -> ChannelDefaults) {
        val ref = channel.value ?: return
        viewModelScope.launch {
            val current = ChannelDefaults.lookup(prefs.channelDefaults.first(), ref.id) ?: ChannelDefaults()
            prefs.setChannelDefaults(ref.id, change(current))
        }
    }

    fun setSpeed(v: Float?) { channel.value?.let { ref -> viewModelScope.launch { prefs.setChannelSpeed(ref.id, v) } } }
    fun setAudioOnly(v: Boolean?) = update { it.copy(audioOnly = v) }
    fun setQuality(height: Int?) = update { it.copy(qualityHeight = height) }
    fun setCaption(lang: String?) = update { it.copy(captionLang = lang) }
    fun setSkipIntro(sec: Int) = update { it.copy(skipIntroSec = sec.coerceIn(0, 600)) }
    fun setSkipOutro(sec: Int) = update { it.copy(skipOutroSec = sec.coerceIn(0, 600)) }
    fun setBoost(db: Int?) = update { it.copy(volumeBoostDb = db) }
    fun setPreset(name: String?) = update { it.copy(soundPreset = name) }
    fun setShortsTab(v: Boolean?) = update { it.copy(showShortsTab = v) }
    fun setAutoDownload(v: Boolean) = update { it.copy(autoDownload = v) }
    fun setAutoDelete(v: Boolean?) = update { it.copy(autoDeleteWatched = v) }

    fun addKeyword(word: String) {
        val w = word.trim().take(40)
        if (w.isNotEmpty()) update { d -> if (d.hiddenKeywords.any { it.equals(w, ignoreCase = true) }) d else d.copy(hiddenKeywords = d.hiddenKeywords + w) }
    }
    fun removeKeyword(word: String) = update { d -> d.copy(hiddenKeywords = d.hiddenKeywords - word) }

    // ---- blur (lives in the blur settings) ----
    fun setBlurNever(on: Boolean) {
        val ref = channel.value ?: return
        viewModelScope.launch { if (on) blurStore.addToWhitelist(ref) else blurStore.removeFromWhitelist(ref.id) }
    }
    private fun blurOverride(change: (ChannelBlurOverride) -> ChannelBlurOverride) {
        val ref = channel.value ?: return
        viewModelScope.launch {
            val cur = blurStore.settings.first().channelOverrides[ref.id] ?: ChannelBlurOverride()
            blurStore.setChannelOverride(ref.id, change(cur))
        }
    }
    fun setBlurForce(v: Boolean?) = blurOverride { it.copy(force = v) }
    fun setBlurCoverage(c: BodyCoverage?) = blurOverride { it.copy(coverage = c) }
    fun setBlurGender(g: GenderRule?) = blurOverride { it.copy(gender = g) }

    // ---- music remover (lives in the music settings): 0 = follow the general switch, 1 = never here, 2 = always here ----
    fun setMusic(mode: Int) {
        val ref = channel.value ?: return
        viewModelScope.launch {
            if (mode == 1) musicStore.addToWhitelist(ref) else musicStore.removeFromWhitelist(ref.id)
            musicStore.setAlways(ref.id, mode == 2)
        }
    }

    fun resetAll() {
        val ref = channel.value ?: return
        viewModelScope.launch {
            prefs.setChannelDefaults(ref.id, null)
            prefs.setChannelSpeed(ref.id, null)
            blurStore.setChannelOverride(ref.id, null)
            blurStore.removeFromWhitelist(ref.id)
            musicStore.removeFromWhitelist(ref.id)
            musicStore.setAlways(ref.id, false)
        }
    }
}
