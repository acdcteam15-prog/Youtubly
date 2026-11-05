package com.youtubly.app.presentation.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.youtubly.app.data.local.datastore.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ScreenPolicyViewModel @Inject constructor(prefs: UserPreferences) : ViewModel() {
    /** Screen stays on while listening in audio mode (default: no, so it can turn off). */
    val keepOnInAudio: StateFlow<Boolean> = prefs.keepScreenOnAudio.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** Screen stays on while watching video (default: yes). */
    val keepOnInVideo: StateFlow<Boolean> = prefs.keepScreenOnVideo.stateIn(viewModelScope, SharingStarted.Eagerly, true)
}

/**
 * Keeps the screen awake exactly while something is playing, following the two switches in Settings:
 * in audio mode the screen is allowed to turn off unless "Keep screen on in audio mode" is on.
 */
@Composable
fun KeepScreenOnWhilePlaying(player: Player, audioMode: Boolean, vm: ScreenPolicyViewModel = hiltViewModel()) {
    val keepAudio by vm.keepOnInAudio.collectAsStateWithLifecycle()
    val keepVideo by vm.keepOnInVideo.collectAsStateWithLifecycle()
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        playing = player.isPlaying
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val activity = LocalContext.current as? Activity
    val keepOn = playing && (if (audioMode) keepAudio else keepVideo)
    DisposableEffect(activity, keepOn) {
        if (keepOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
