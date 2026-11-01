package com.youtubly.app.presentation.downloads

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.youtubly.app.playback.PipState
import com.youtubly.app.presentation.components.ErrorView
import com.youtubly.app.presentation.components.SkeletonFeed

/**
 * Plays a saved (downloaded) file through the same singleton PlayerController the streamed
 * player uses, so downloaded video/audio gets the same background-playback service and
 * picture-in-picture support instead of being handed off to an external app.
 */
@OptIn(UnstableApi::class)
@Composable
fun LocalPlayerScreen(
    downloadId: String,
    onBack: () -> Unit,
    viewModel: LocalPlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val inPip by PipState.inPip

    LaunchedEffect(downloadId) { viewModel.load(downloadId) }

    // The video area sits right under the status bar on a black background, so the
    // clock/battery icons must be light here regardless of the app theme.
    com.youtubly.app.presentation.components.LightStatusBarIcons()

    // Keep the screen awake exactly while something is actually playing.
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(activity, viewModel.playerController.player) {
        val player = viewModel.playerController.player
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        if (player.isPlaying) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var fullscreen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val abLoop by viewModel.playerController.abLoop.collectAsStateWithLifecycle()
    var autoplayNext by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(autoplayNext) { viewModel.autoplayNext = autoplayNext }
    if (showSettings) LocalPlaybackSettingsSheet(viewModel, isAudioOnlyFile = (state as? LocalPlayerUiState.Ready)?.item?.isAudioOnly == true) { showSettings = false }
    var dragY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    com.youtubly.app.presentation.components.FullscreenEffect(fullscreen && !inPip)
    // Screen lock: covers the WHOLE screen (video, title, next/previous, back gesture); only a
    // double-tap anywhere unlocks. Saved so rotating / PiP-ing doesn't silently unlock it.
    var screenLocked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val boostDbNow by viewModel.playerController.boostDb.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = fullscreen && !screenLocked) { fullscreen = false }
    androidx.activity.compose.BackHandler(enabled = screenLocked) { /* swallowed while locked */ }

    Box(Modifier.fillMaxSize().background(if (inPip) Color.Black else MaterialTheme.colorScheme.background)) {
        when (val s = state) {
            is LocalPlayerUiState.Loading -> if (!inPip) SkeletonFeed(count = 1)
            is LocalPlayerUiState.NotFound ->
                if (!inPip) ErrorView("This file is no longer available.", onRetry = { viewModel.load(downloadId) })
            // Column, not the outer Box: stacked in a Box the title was drawn over the status
            // bar (the Arabic text on top of the clock) instead of under the video.
            is LocalPlayerUiState.Ready -> Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .graphicsLayer { translationY = dragY; val k = 1f - (dragY / 2000f).coerceIn(0f, 0.25f); scaleX = k; scaleY = k }
                        .then(if (inPip || fullscreen) Modifier.fillMaxSize() else Modifier.statusBarsPadding().aspectRatio(16f / 9f))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                player = viewModel.playerController.player
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                useController = false // Compose VideoControls instead
                            }
                        },
                        // Re-attach if another PlayerView (mini player, streamed player) took the
                        // video output in the meantime — otherwise audio plays over a black box.
                        update = { view ->
                            val p = viewModel.playerController.player
                            if (view.player !== p) view.player = p
                            view.requestLayout()
                        },
                        onRelease = { it.player = null }
                    )
                    if (!inPip) {
                        if (s.item.isAudioOnly) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Headphones, null,
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.fillMaxWidth().padding(48.dp)
                                )
                            }
                        }
                        com.youtubly.app.presentation.components.VideoControls(
                            player = viewModel.playerController.player,
                            fullscreen = fullscreen,
                            onMinimize = { if (fullscreen) fullscreen = false else onBack() },
                            onToggleFullscreen = { fullscreen = !fullscreen },
                            onDragY = { dragY = it },
                            onSettings = { showSettings = true },
                            showPipButton = !s.item.isAudioOnly,
                            loopAMs = abLoop.a,
                            loopBMs = abLoop.b,
                            loopActive = abLoop.isActive,
                            autoplay = if (s.hasNext) autoplayNext else null,
                            onToggleAutoplay = { autoplayNext = !autoplayNext },
                            lockedState = screenLocked,
                            onLockedChange = { screenLocked = it },
                            boostDb = boostDbNow,
                            onBoostChange = { viewModel.setBoost(it) }
                        )
                    }
                }
                if (!inPip && !fullscreen) {
                    Text(
                        s.item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    if (s.hasNext || s.hasPrevious) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            IconButton(onClick = { viewModel.playPrevious() }, enabled = s.hasPrevious) {
                                Icon(Icons.Rounded.SkipPrevious, "Previous download")
                            }
                            Spacer(Modifier.width(24.dp))
                            IconButton(onClick = { viewModel.playNext() }, enabled = s.hasNext) {
                                Icon(Icons.Rounded.SkipNext, "Next download")
                            }
                        }
                    }
                }
            }
        }
        if (screenLocked && !inPip) com.youtubly.app.presentation.components.FullScreenTouchLock(onUnlock = { screenLocked = false })
    }
}

private val LOCAL_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val LOCAL_BOOSTS = com.youtubly.app.playback.VolumeBoost.LEVELS_DB

/** Same options as the online player's sheet, minus stream-only ones (quality, captions). */
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LocalPlaybackSettingsSheet(viewModel: LocalPlayerViewModel, isAudioOnlyFile: Boolean, onDismiss: () -> Unit) {
    val controller = viewModel.playerController
    val skipSilence by controller.skipSilence.collectAsStateWithLifecycle()
    val boostDb by controller.boostDb.collectAsStateWithLifecycle()
    val eq by controller.eqPreset.collectAsStateWithLifecycle()
    val audioMode by controller.audioMode.collectAsStateWithLifecycle()
    val loop by controller.abLoop.collectAsStateWithLifecycle()
    val sleep by viewModel.sleepTimer.state.collectAsStateWithLifecycle()
    val running = sleep as? com.youtubly.app.playback.SleepTimer.State.Running
    // Speed lives on the player; follow it so the chip stays right after changes from elsewhere.
    var speed by remember { mutableStateOf(controller.player.playbackParameters.speed) }
    DisposableEffect(controller.player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackParametersChanged(p: androidx.media3.common.PlaybackParameters) { speed = p.speed }
        }
        controller.player.addListener(l)
        onDispose { controller.player.removeListener(l) }
    }
    fun fmt(ms: Long): String { val t = ms / 1000; return "%d:%02d".format(t / 60, t % 60) }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Section("Speed")
            Chips { LOCAL_SPEEDS.forEach { sp -> Chip(if (sp == 1f) "Normal" else "${sp}×", speed == sp) { viewModel.setSpeed(sp) } } }

            Section("Sleep timer")
            Chips {
                Chip("Off", running == null) { viewModel.sleepTimer.cancel() }
                listOf(15, 30, 45, 60, 90).forEach { m -> Chip("$m min", false) { viewModel.sleepTimer.startMinutes(m) } }
                Chip("End of video", running?.endOfVideo == true) { viewModel.sleepTimer.startEndOfVideo() }
            }
            if (running != null) Text(
                if (running.endOfVideo) "Stops at the end of this video" else "Stops in ${fmt(running.remainingMs)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp)
            )

            Section("Audio")
            Chips {
                if (!isAudioOnlyFile) Chip("Listen only", audioMode) { viewModel.toggleListenOnly() }
                Chip("Skip silence", skipSilence) { viewModel.setSkipSilence(!skipSilence) }
            }
            Section("Volume boost")
            Chips { LOCAL_BOOSTS.forEach { db -> Chip(if (db == 0) "Off" else "+$db dB", boostDb == db) { viewModel.setBoost(db) } } }

            Section("Sound")
            Chips { com.youtubly.app.domain.audio.EqPreset.entries.forEach { p -> Chip(p.label, eq == p) { controller.setEqPreset(p) } } }

            Section("A–B loop")
            Chips {
                Chip(loop.a?.let { "A · ${fmt(it)}" } ?: "Set A", loop.a != null) { controller.setLoopA() }
                Chip(loop.b?.let { "B · ${fmt(it)}" } ?: "Set B", loop.b != null) { controller.setLoopB() }
                if (loop.a != null && loop.b != null) Chip(if (loop.enabled) "Loop on" else "Loop off", loop.enabled) { controller.setLoopEnabled(!loop.enabled) }
                if (loop.a != null || loop.b != null) Chip("Clear", false) { controller.clearLoop() }
            }
        }
    }
}

@Composable
private fun Section(title: String) = Text(
    title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
)

@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) =
    androidx.compose.material3.FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        shape = MaterialTheme.shapes.extraLarge,
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = Color.White
        )
    )
