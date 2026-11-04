package com.youtubly.app.presentation.player

import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import android.view.ViewGroup
import kotlin.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.youtubly.app.presentation.components.sharedThumbnail
import com.youtubly.app.domain.model.Video
import com.youtubly.app.playback.PipState
import com.youtubly.app.presentation.components.ErrorView
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.VideoCard
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.components.popOnChange
import com.youtubly.app.presentation.components.formatCount
import com.youtubly.app.presentation.components.prettyDate
import com.youtubly.app.presentation.components.formatDuration
import com.youtubly.app.presentation.components.rememberBranding
import com.youtubly.app.presentation.theme.BrandGradient
import com.youtubly.app.presentation.theme.BrandGradientH
import com.youtubly.app.presentation.theme.Violet
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    videoUrl: String,
    startMs: Long? = null,
    onBack: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (String) -> Unit,
    /** Changes on every open request, so re-opening the same video still re-runs the load (seek). */
    requestNonce: Long = 0L,
    /** Sheet position, 0 = expanded … 1 = collapsed mini bar. Read in layout/draw only. */
    collapseProgress: () -> Float = { 0f },
    onExpand: () -> Unit = {},
    onClose: () -> Unit = {},
    onSheetDrag: ((Float) -> Unit)? = null,
    onSheetDragEnd: ((Float) -> Unit)? = null,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val collapsed by remember { androidx.compose.runtime.derivedStateOf { collapseProgress() > 0.02f } }
    // The video controls own the swipe-down gesture, so they must stay composed for the WHOLE drag.
    // Removing them at 2% (the old `!collapsed` gate) killed the gesture mid-swipe and left the sheet
    // parked part-way down. They now fade out while dragging and leave only once fully collapsed.
    val fullyCollapsed by remember { androidx.compose.runtime.derivedStateOf { collapseProgress() > 0.98f } }
    val mostlyExpanded by remember { androidx.compose.runtime.derivedStateOf { collapseProgress() < 0.5f } }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var toast by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val inPip by PipState.inPip

    LaunchedEffect(videoUrl, startMs, requestNonce) { viewModel.load(videoUrl, startMs = startMs) }
    // Leaving picture-in-picture: make sure the details / settings match what's playing.
    LaunchedEffect(inPip) { if (!inPip) viewModel.resyncAfterPip(videoUrl) }
    // If playback was stopped while we were away (PiP closed, mini player ✕), reload this
    // video when the screen comes back instead of showing a dead player.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, videoUrl) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && viewModel.playerController.currentVideoId == null &&
                viewModel.uiState.value is PlayerUiState.Success
            ) viewModel.load(videoUrl, force = true)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { msg ->
            toast = msg
            delay(2400)
            if (toast == msg) toast = null
        }
    }
    // The video area sits right under the status bar on a black background, so the
    // clock/battery icons must be light here regardless of the app theme.
    // Only while the sheet is (mostly) expanded — a collapsed mini bar must not change the
    // status bar of the screen underneath.
    if (mostlyExpanded) com.youtubly.app.presentation.components.LightStatusBarIcons()

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
    // Touch lock covers the WHOLE screen (not just the video) and swallows back presses too.
    var screenLocked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val boostDbNow by viewModel.playerController.boostDb.collectAsStateWithLifecycle()
    var dragY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val wide = com.youtubly.app.presentation.components.LocalWideLayout.current

    // Phase 8 "Rotate to fullscreen" (phones): landscape opens fullscreen, upright exits it —
    // but only a fullscreen that rotation itself started, never one the user chose with the button.
    val rotatePref by com.youtubly.app.presentation.components.LocalRotateToFullscreen.current
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var rotatedIn by remember { mutableStateOf(false) }
    LaunchedEffect(landscape) {
        if (!rotatePref || wide || inPip) return@LaunchedEffect
        if (landscape && !fullscreen && !collapsed && state is PlayerUiState.Success) {
            fullscreen = true; rotatedIn = true
        } else if (!landscape && fullscreen && rotatedIn) {
            fullscreen = false; rotatedIn = false
        }
    }
    LaunchedEffect(fullscreen) { if (!fullscreen) rotatedIn = false }
    com.youtubly.app.presentation.components.FullscreenEffect(fullscreen && !inPip, lockLandscape = !rotatedIn)
    androidx.activity.compose.BackHandler(enabled = fullscreen && !screenLocked) { fullscreen = false }
    androidx.activity.compose.BackHandler(enabled = screenLocked && !inPip) { /* swallowed while locked */ }

    val success = state as? PlayerUiState.Success
    val autoplayNext by viewModel.autoplayNext.collectAsStateWithLifecycle()
    val queueActive = viewModel.queue.collectAsStateWithLifecycle().value != null
    val abLoop by viewModel.playerController.abLoop.collectAsStateWithLifecycle()
    val chapterTitles = remember(success?.info?.video?.id, success?.info?.chapters) {
        success?.info?.chapters?.map { it.startSeconds * 1000L to it.title }.orEmpty()
    }
    val chapterMarkers = remember(success?.info?.video?.id, success?.info?.chapters) {
        success?.info?.chapters?.filter { it.startSeconds > 0 }?.map { it.startSeconds * 1000L }?.toLongArray() ?: LongArray(0)
    }

    val pageBg = MaterialTheme.colorScheme.background
    val miniBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val statusTopPx = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(androidx.compose.ui.platform.LocalDensity.current)
    val miniVideoWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { (MiniBarHeight * 16f / 9f).roundToPx() }
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(if (inPip) Color.Black else androidx.compose.ui.graphics.lerp(pageBg, miniBg, collapseProgress()))
        }
    ) {
        // Phase 8: on tablets the expanded player is two panes — video left, details right.
        val twoPane = wide && !inPip && !fullscreen
        val details: @Composable (Modifier) -> Unit = { m ->
            AnimatedContent(
                modifier = m,
                targetState = state,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
                contentKey = { it::class },
                label = "playerState"
            ) { s ->
                when (s) {
                    is PlayerUiState.Loading -> SkeletonFeed(count = 2)
                    is PlayerUiState.Error -> ErrorView(s.message, onRetry = { viewModel.load(videoUrl, force = true) })
                    is PlayerUiState.Success -> PlayerDetails(s, viewModel, onVideoClick, onChannelClick)
                }
            }
        }
        Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            // ---- Player surface ----
            // One surface for the full player and the mini bar: it just shrinks, so there is
            // never a re-attach (the black flash of the old separate mini player).
            Box(
                Modifier
                    .graphicsLayer { translationY = dragY; val k = 1f - (dragY / 2000f).coerceIn(0f, 0.25f); scaleX = k; scaleY = k }
                    .background(Color.Black)
                    .then(
                        when {
                            inPip || fullscreen -> Modifier.fillMaxSize()
                            else -> Modifier.layout { measurable, constraints ->
                                val p = collapseProgress().coerceIn(0f, 1f)
                                val w = (constraints.maxWidth + (miniVideoWidthPx - constraints.maxWidth) * p).toInt()
                                val h = w * 9 / 16
                                val pad = (statusTopPx * (1f - p)).toInt()
                                // Black band under the status bar while expanded, gone when collapsed.
                                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                                layout(w, h + pad) { placeable.place(0, pad) }
                            }
                        }
                    )
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            player = viewModel.playerController.player
                            // Opaque black behind the video: if the GPU pipeline ever hands over a transparent
                            // frame, it must not show the window background (white) through the surface.
                            setBackgroundColor(android.graphics.Color.BLACK)
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            // Controls are drawn in Compose (VideoControls) — Media3's default
                            // controller had a tall bottom bar that covered half the video.
                            useController = false
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        }
                    },
                    // Re-attach if another PlayerView took the video output meanwhile.
                    update = { view ->
                        val p = viewModel.playerController.player
                        if (view.player !== p) view.player = p
                        view.requestLayout()
                    },
                    onRelease = { it.player = null }
                )
                if (!inPip) {
                    PosterOverlay(
                        videoUrl = videoUrl,
                        fallbackThumb = success?.info?.video?.thumbnailUrl,
                        channel = com.youtubly.app.blur.channelKeyOf(success?.info?.video?.uploaderUrl),
                        player = viewModel.playerController.player,
                        currentId = { viewModel.playerController.currentVideoId },
                        loaded = success != null
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        success?.audioOnly == true, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()
                    ) { AudioOnlyOverlay() }
                    if (!fullyCollapsed) com.youtubly.app.presentation.components.VideoControls(
                        player = viewModel.playerController.player,
                        fullscreen = fullscreen,
                        onMinimize = { if (fullscreen) fullscreen = false else onBack() },
                        onToggleFullscreen = { fullscreen = !fullscreen },
                        onDragY = { dragY = it },
                        modifier = Modifier.graphicsLayer { alpha = (1f - collapseProgress() * 4f).coerceIn(0f, 1f) },
                        onSheetDrag = onSheetDrag,
                        onSheetDragEnd = onSheetDragEnd,
                        onSettings = if (success != null) ({ showSettings = true }) else null,
                        showPipButton = success?.audioOnly != true,
                        chapterMarksMs = chapterMarkers,
                        loopAMs = abLoop.a,
                        loopBMs = abLoop.b,
                        loopActive = abLoop.isActive,
                        previewFrames = success?.info?.previewFrames,
                        // Only playlists/queues autoplay, so the switch shows only when one is active.
                        autoplay = if (queueActive) autoplayNext else null,
                        onToggleAutoplay = { viewModel.setAutoplayNext(!autoplayNext) },
                        chapters = chapterTitles,
                        lockedState = screenLocked,
                        onLockedChange = { screenLocked = it },
                        boostDb = boostDbNow,
                        onBoostChange = { viewModel.setBoost(it) }
                    )
                    if (success?.dataSaver == true && !fullscreen && !collapsed) {
                        DataSaverPill(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 52.dp))
                    }
                }
            }
            if (collapsed && !inPip && !fullscreen) {
                MiniBarControls(
                    title = success?.info?.video?.title ?: "Loading…",
                    channel = success?.info?.video?.uploaderName,
                    player = viewModel.playerController.player,
                    onClose = onClose,
                    modifier = Modifier.weight(1f).graphicsLayer { alpha = collapseProgress().coerceIn(0f, 1f) }
                )
            }
          }

            if (!inPip && !fullscreen && !twoPane) {
                details(Modifier.graphicsLayer { alpha = (1f - collapseProgress() * 2f).coerceIn(0f, 1f) })
            }
        }
        if (twoPane) {
            // Right pane: fixed width while measured, but only (1 - collapse) of it is shown, so
            // collapsing slides it away and the mini bar gets the full width.
            val minPanePx = with(androidx.compose.ui.platform.LocalDensity.current) { 360.dp.roundToPx() }
            Box(
                Modifier
                    .fillMaxHeight()
                    .layout { measurable, constraints ->
                        val paneW = maxOf((constraints.maxWidth * 0.38f).toInt(), minPanePx).coerceAtMost(constraints.maxWidth / 2)
                        val shown = (paneW * (1f - collapseProgress().coerceIn(0f, 1f))).toInt()
                        val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(paneW, constraints.maxHeight))
                        layout(shown, constraints.maxHeight) { placeable.place(0, 0) }
                    }
                    .clipToBounds()
                    .graphicsLayer { alpha = (1f - collapseProgress() * 2f).coerceIn(0f, 1f) }
            ) {
                details(Modifier.fillMaxSize().statusBarsPadding())
            }
        }
        }

        // ---- Full-screen touch lock (drawn last = above everything in the player) ----
        if (screenLocked && !inPip && mostlyExpanded) {
            com.youtubly.app.presentation.components.FullScreenTouchLock(onUnlock = { screenLocked = false })
        }

        // ---- Floating toast ----
        if (!inPip && mostlyExpanded) {
            androidx.compose.animation.AnimatedVisibility(
                visible = toast != null,
                enter = slideInVertically(spring(dampingRatio = 0.7f)) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(BrandGradientH)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Shield, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(toast.orEmpty(), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (showSettings && success != null && !inPip) {
        PlaybackSettingsSheet(success, viewModel, onDismiss = { showSettings = false })
    }
}

@Composable
private fun PlayerDetails(
    s: PlayerUiState.Success,
    viewModel: PlayerViewModel,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (String) -> Unit
) {
    val video = s.info.video
    val branding = rememberBranding(video.id)
    var showDownload by remember(video.id) { mutableStateOf(false) }
    if (showDownload) {
        com.youtubly.app.presentation.components.DownloadQualitySheet(
            info = s.info,
            onPick = { showDownload = false; viewModel.download(it) },
            onDismiss = { showDownload = false }
        )
    }
    var saveSheet by remember(video.id) { mutableStateOf(false) }
    if (saveSheet) com.youtubly.app.presentation.components.SaveToPlaylistSheet(video, onDismiss = { saveSheet = false })
    var studySheet by remember(video.id) { mutableStateOf(false) }
    if (studySheet) StudySheet(s, viewModel, onDismiss = { studySheet = false })
    val playlistRepo = com.youtubly.app.presentation.components.LocalPlaylists.current
    val inPlaylists by remember(video.id, playlistRepo) {
        playlistRepo?.playlistsContaining(video.id) ?: kotlinx.coroutines.flow.flowOf(emptySet())
    }.collectAsState(initial = emptySet())
    val queue by viewModel.queue.collectAsState()
    var expanded by remember(video.id) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, spring(stiffness = Spring.StiffnessLow), label = "chevron")
    val liked = s.account?.liked == true
    val subscribed = s.account?.subscribed == true

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Once the title block scrolls away, pin a compact title bar under the (already sticky) video.
    val showCompact by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    Box {
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Column(Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)).clickable { expanded = !expanded }) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (expanded) video.title else (branding?.title ?: video.title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Rounded.ExpandMore, "More", modifier = Modifier.rotate(chevron))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            video.viewCount.takeIf { it >= 0 }?.let { "${formatCount(it)} views" },
                            video.uploadDate?.let(::prettyDate),
                            branding?.title?.let { if (expanded) null else "tap for original title" }
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Channel row + Subscribe
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.large)
                            .clickable(enabled = video.uploaderUrl != null) { video.uploaderUrl?.let(onChannelClick) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(42.dp).clip(CircleShape).background(BrandGradient), contentAlignment = Alignment.Center) {
                            Text(video.uploaderName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            video.uploaderName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    AnimatedContent(
                        targetState = subscribed,
                        modifier = Modifier.popOnChange(subscribed, peak = 1.12f),
                        transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.85f)) togetherWith fadeOut(tween(120)) },
                        label = "sub"
                    ) { isSub ->
                        if (isSub) {
                            OutlinedButton(onClick = viewModel::toggleSubscribe) {
                                Icon(Icons.Rounded.NotificationsActive, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Subscribed")
                            }
                        } else {
                            Button(
                                onClick = viewModel::toggleSubscribe,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)
                            ) { Text("Subscribe") }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                VoteBar(s.info.likeCount, s.dislikeCount)
                Spacer(Modifier.height(16.dp))
            }
        }

        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Box(Modifier.popOnChange(liked)) {
                        ActionPill(
                            icon = if (liked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
                            label = if (liked) "Liked" else "Like",
                            highlighted = liked,
                            onClick = viewModel::toggleLike
                        )
                    }
                }
                item {
                    ActionPill(
                        icon = if (s.inWatchLater) Icons.Rounded.WatchLater else Icons.Outlined.WatchLater,
                        label = if (s.inWatchLater) "Saved" else "Watch later",
                        highlighted = s.inWatchLater,
                        onClick = viewModel::toggleWatchLater
                    )
                }
                if (playlistRepo != null) item {
                    ActionPill(
                        icon = Icons.Rounded.PlaylistAdd,
                        label = if (inPlaylists.isNotEmpty()) "Saved to playlist" else "Save",
                        highlighted = inPlaylists.isNotEmpty(),
                        onClick = { saveSheet = true }
                    )
                }
                item {
                    ActionPill(
                        icon = if (s.audioOnly) Icons.Rounded.Videocam else Icons.Rounded.Headphones,
                        label = if (s.audioOnly) "Watch video" else "Listen only",
                        highlighted = s.audioOnly,
                        onClick = viewModel::toggleAudioOnly
                    )
                }
                item {
                    ActionPill(
                        icon = Icons.Rounded.Speed,
                        label = formatSpeed(s.speed),
                        highlighted = s.speed != 1f,
                        onClick = viewModel::cycleSpeed
                    )
                }
                item { ActionPill(Icons.Rounded.EditNote, "Notes and transcript", onClick = { studySheet = true }) }
                item { ActionPill(Icons.Rounded.Download, "Download", onClick = { showDownload = true }) }
                item { SharePill(video.url, branding?.title ?: video.title, video.duration.toInt(), viewModel.playerController) }
            }
        }

        queue?.let { q ->
            item(key = "queue") {
                QueueBar(
                    q,
                    onPrevious = { viewModel.skipQueue(-1) },
                    onNext = { viewModel.skipQueue(+1) },
                    onJump = viewModel::jumpInQueue,
                    onLeave = viewModel::leaveQueue
                )
            }
        }

        if (!s.info.description.isNullOrBlank()) {
            item(key = "description") { DescriptionCard(s.info.description!!) }
        }

        if (s.info.chapters.isNotEmpty()) {
            item(key = "chapters") { ChaptersCarousel(s, viewModel) }
        }

        item {
            androidx.compose.animation.AnimatedVisibility(s.segments.isNotEmpty(), enter = fadeIn() + scaleIn(initialScale = 0.9f)) {
                SponsorStrip(s)
            }
        }

        if (s.info.relatedVideos.isNotEmpty()) {
            item {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 4.dp)
                )
            }
            itemsIndexed(s.info.relatedVideos, key = { i, v -> "${v.id}_$i" }) { i, v ->
                VideoCard(
                    v,
                    onClick = { onVideoClick(v) },
                    index = i
                )
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = showCompact,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { scope.launch { listState.animateScrollToItem(0) } }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.youtubly.app.presentation.components.ChannelAvatar(video, size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                branding?.title ?: video.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Rounded.ExpandMore, "Back to top", modifier = Modifier.rotate(180f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }

}

private val BOOSTS = com.youtubly.app.playback.VolumeBoost.LEVELS_DB
private val TRANSLATE_TO = listOf("en" to "English", "ar" to "Arabic")

@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSettingsSheet(s: PlayerUiState.Success, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            val controller = viewModel.playerController
            val skipSilence by controller.skipSilence.collectAsStateWithLifecycle()
            val boostDb by controller.boostDb.collectAsStateWithLifecycle()
            val eq by controller.eqPreset.collectAsStateWithLifecycle()
            val loop by controller.abLoop.collectAsStateWithLifecycle()

            SheetSection("Quality")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val autoLabel = s.options.maxHeight?.let { "Auto (≤${it}p)" } ?: "Auto"
                SettingChip(autoLabel, s.options.qualityHeight == null && !s.audioOnly) { viewModel.setQuality(null) }
                s.heights.forEach { h ->
                    SettingChip(
                        when {
                            h >= 2160 -> "${h}p 4K"
                            h >= 1440 -> "${h}p 2K"
                            h >= 720 -> "${h}p HD"
                            else -> "${h}p"
                        },
                        s.options.qualityHeight == h && !s.audioOnly
                    ) { viewModel.setQuality(h) }
                }
            }

            if (s.dataSaver) {
                Text(
                    "Data saver is on (mobile data) — capped at 360p",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            SheetSection("Playback")
            val autoplay by viewModel.autoplayNext.collectAsStateWithLifecycle()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip(if (autoplay) "Autoplay next: on" else "Autoplay next: off", autoplay) { viewModel.setAutoplayNext(!autoplay) }
            }

            SheetSection("Speed")
            // Slider: 0.25x to 3x in steps of 0.05. Live while dragging, saved when you let go; -/+ for fine steps.
            run {
                val snap = { v: Float -> (Math.round(v * 20f) / 20f).coerceIn(0.25f, 3f) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.setSpeed(snap(s.speed - 0.05f)) }) { Icon(Icons.Rounded.Remove, "Slower") }
                    androidx.compose.material3.Slider(
                        value = s.speed.coerceIn(0.25f, 3f),
                        onValueChange = { viewModel.previewSpeed(snap(it)) },
                        onValueChangeFinished = { viewModel.setSpeed(s.speed) },
                        valueRange = 0.25f..3f,
                        steps = 54,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.setSpeed(snap(s.speed + 0.05f)) }) { Icon(Icons.Rounded.Add, "Faster") }
                }
                Text(
                    // Tap the value to go back to normal speed.
                    formatSpeed(s.speed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).clickable { viewModel.setSpeed(1f) }.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip(
                    if (s.videoSpeedRemembered) "Remembered for this video" else "Remember for this video",
                    s.videoSpeedRemembered
                ) { viewModel.rememberSpeedForVideo() }
                if (s.videoSpeedRemembered) SettingChip("Reset video", false) { viewModel.resetVideoSpeed() }
                SettingChip(
                    if (s.channelSpeedRemembered) "Remembered for this channel" else "Remember for this channel",
                    s.channelSpeedRemembered
                ) { viewModel.rememberSpeedForChannel() }
                if (s.channelSpeedRemembered) SettingChip("Reset", false) { viewModel.resetChannelSpeed() }
            }


            SheetSection("This channel")
            val chDefaults = s.channelDefaults
            Text(
                if (chDefaults == null) "Save today's choices (audio only, quality, captions) for every video of this channel."
                else "Saved: " + com.youtubly.app.domain.playback.ChannelDefaults.summary(chDefaults),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip(if (chDefaults == null) "Save current settings" else "Update saved settings", chDefaults != null) { viewModel.saveChannelDefaults() }
                if (chDefaults != null) SettingChip("Clear", false) { viewModel.clearChannelDefaults() }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Skip intro", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.setSkipIntro((chDefaults?.skipIntroSec ?: 0) - 5) }) { Icon(Icons.Rounded.Remove, "Less") }
                Text("${chDefaults?.skipIntroSec ?: 0} s", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { viewModel.setSkipIntro((chDefaults?.skipIntroSec ?: 0) + 5) }) { Icon(Icons.Rounded.Add, "More") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip("Skip intro until here", false) { viewModel.skipIntroToHere() }
            }

            SheetSection("Sleep timer")
            val sleep by viewModel.sleepTimer.state.collectAsStateWithLifecycle()
            val running = sleep as? com.youtubly.app.playback.SleepTimer.State.Running
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip("Off", running == null) { viewModel.sleepTimer.cancel() }
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    SettingChip("$m min", false) { viewModel.sleepTimer.startMinutes(m) }
                }
                SettingChip("End of video", running?.endOfVideo == true) { viewModel.sleepTimer.startEndOfVideo() }
            }
            if (running != null) {
                Text(
                    (if (running.endOfVideo) "Stops at the end of this video" else "Stops in ${formatDuration(running.remainingMs / 1000)}") +
                        " · fades out over the last 30 s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            SheetSection("Audio")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip("Listen only", s.audioMode) { viewModel.toggleAudioOnly() }
                SettingChip("Skip silence", skipSilence) { viewModel.setSkipSilence(!skipSilence) }
            }
            SheetSection("Volume boost")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BOOSTS.forEach { db ->
                    SettingChip(if (db == 0) "Off" else "+$db dB", boostDb == db) { viewModel.setBoost(db) }
                }
            }

            SheetSection("Sound")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.youtubly.app.domain.audio.EqPreset.entries.forEach { p ->
                    SettingChip(p.label, eq == p) { controller.setEqPreset(p) }
                }
            }

            SheetSection("A–B loop")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip(loop.a?.let { "A · ${formatDuration(it / 1000)}" } ?: "Set A", loop.a != null) { viewModel.setLoopA() }
                SettingChip(loop.b?.let { "B · ${formatDuration(it / 1000)}" } ?: "Set B", loop.b != null) { viewModel.setLoopB() }
                if (loop.a != null && loop.b != null) {
                    SettingChip(if (loop.enabled) "Loop on" else "Loop off", loop.enabled) { viewModel.setLoopEnabled(!loop.enabled) }
                }
                if (loop.a != null || loop.b != null) SettingChip("Clear", false) { viewModel.clearLoop() }
            }

            SheetSection("Captions")
            if (s.info.captions.isEmpty()) {
                Text("No captions for this video", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingChip("Off", s.options.caption == null) { viewModel.setCaption(null) }
                    s.info.captions.forEach { c ->
                        SettingChip(c.label, s.options.caption == c && s.options.translateTo == null) { viewModel.setCaption(c) }
                    }
                }
                val base = s.options.caption ?: s.info.captions.first()
                SheetSection("Auto-translate captions")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TRANSLATE_TO.forEach { (code, name) ->
                        SettingChip(name, s.options.translateTo == code) { viewModel.setCaption(base, translateTo = code) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
    )
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = MaterialTheme.shapes.extraLarge,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = Color.White
        )
    )
}

@Composable
private fun VoteBar(likes: Long?, dislikes: Long?) {
    val total = (likes ?: 0) + (dislikes ?: 0)
    val target = if (total > 0 && likes != null) likes.toFloat() / total else 0f
    val ratio by animateFloatAsState(target, tween(900), label = "ratio")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ThumbUp, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            AnimatedCount(likes)
            Spacer(Modifier.weight(1f))
            AnimatedCount(dislikes)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ThumbDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(Modifier.fillMaxWidth(ratio).height(6.dp).clip(CircleShape).background(BrandGradientH))
        }
    }
}

@Composable
private fun AnimatedCount(value: Long?) {
    AnimatedContent(
        targetState = value,
        transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
        label = "count"
    ) { v ->
        Text(v?.let(::formatCount) ?: "—", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** "Playing from <playlist> · 3 / 12" with previous / next, expandable to the full list. */
@Composable
private fun QueueBar(
    q: com.youtubly.app.playback.PlayQueue,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJump: (Int) -> Unit,
    onLeave: () -> Unit
) {
    var showAll by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .animateContentSize()
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(q.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${q.index + 1} / ${q.items.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.IconButton(onClick = onPrevious, enabled = q.hasPrevious) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
            androidx.compose.material3.IconButton(onClick = onNext, enabled = q.hasNext) { Icon(Icons.Rounded.SkipNext, "Next") }
            androidx.compose.material3.IconButton(onClick = onLeave) { Icon(Icons.Rounded.Close, "Stop playing this playlist") }
        }
        if (showAll) {
            q.items.forEachIndexed { i, v ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onJump(i) }
                        .background(if (i == q.index) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (i == q.index) "▶" else "${i + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (i == q.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(28.dp)
                    )
                    Text(
                        v.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Title, play/pause and close — the right side of the collapsed mini bar. */
@Composable
private fun MiniBarControls(
    title: String,
    channel: String?,
    player: androidx.media3.common.Player,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var playing by remember { mutableStateOf(player.isPlaying || player.playWhenReady) }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onEvents(p: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                playing = p.playWhenReady && p.playbackState != androidx.media3.common.Player.STATE_ENDED
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    Row(modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!channel.isNullOrBlank()) {
                Text(channel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        androidx.compose.material3.IconButton(onClick = {
            when {
                player.playbackState == androidx.media3.common.Player.STATE_ENDED -> { player.seekTo(0); player.play() }
                player.playWhenReady -> player.pause()
                else -> player.play()
            }
        }) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play")
        }
        androidx.compose.material3.IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close player") }
    }
}

@Composable
private fun ActionPill(icon: ImageVector, label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .bounceClick(onClick = onClick)
            .clip(RoundedCornerShape(22.dp))
            .then(
                if (highlighted) Modifier.background(BrandGradientH)
                else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurface
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}

/** Visual map of SponsorBlock segments across the video timeline. */
@Composable
private fun SponsorStrip(s: PlayerUiState.Success) {
    val duration = s.info.video.duration.coerceAtLeast(1).toFloat()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = Violet, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "${s.segments.size} segment${if (s.segments.size == 1) "" else "s"} auto-skipped by SponsorBlock",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Row(Modifier.fillMaxSize()) {
                var cursor = 0f
                s.segments.sortedBy { it.startSeconds }.forEach { seg ->
                    val start = (seg.startSeconds.toFloat() / duration).coerceIn(0f, 1f)
                    val end = (seg.endSeconds.toFloat() / duration).coerceIn(start, 1f)
                    val gap = start - cursor
                    if (gap > 0f) Spacer(Modifier.weight(gap))
                    val w = (end - start).coerceAtLeast(0.004f)
                    Box(Modifier.weight(w).fillMaxSize().background(Violet))
                    cursor = start + w
                }
                val rest = 1f - cursor
                if (rest > 0f) Spacer(Modifier.weight(rest))
            }
        }
    }
}

/**
 * The card's thumbnail, drawn over the video area: it's the destination of the card → player
 * shared-element flight, and a poster until this video's first frame is on screen. Hidden
 * afterwards, but kept composed so the reverse flight on Back always has both ends.
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun PosterOverlay(
    videoUrl: String,
    fallbackThumb: String?,
    channel: String?,
    player: androidx.media3.common.Player,
    currentId: () -> String?,
    loaded: Boolean
) {
    val thumb = remember(videoUrl) { com.youtubly.app.presentation.components.SharedThumbs.get(videoUrl) }
        ?: fallbackThumb ?: youtubeThumbFor(videoUrl)
    val expectedId = remember(videoUrl) { videoIdFromUrl(videoUrl) }
    var rendered by remember(videoUrl) { mutableStateOf(false) }
    DisposableEffect(player, videoUrl) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() {
                // Ignore a frame of whatever was playing before (mini player) — only this video counts.
                val id = currentId()
                if (expectedId == null || id == null || id == expectedId) rendered = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // Safety net (paused re-attach, audio-only): never leave the poster up indefinitely.
    LaunchedEffect(loaded) { if (loaded) { kotlinx.coroutines.delay(2_500); rendered = true } }

    val shared = com.youtubly.app.presentation.components.LocalSharedTransitionScope.current
    val context = LocalContext.current
    com.youtubly.app.presentation.blur.BlurredAsyncImage(
        model = coil3.request.ImageRequest.Builder(context)
            .data(thumb)
            .placeholderMemoryCacheKey(thumb) // the card's cached bitmap, so frame 1 isn't blank
            .build(),
        contentDescription = null,
        channel = channel,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .sharedThumbnail(videoUrl)
            // Read in the draw phase so it flips on the very first frame of a Back transition.
            .graphicsLayer { alpha = if (!rendered || shared?.isTransitionActive == true) 1f else 0f }
    )
}

private fun videoIdFromUrl(url: String): String? {
    val u = android.net.Uri.parse(url)
    return u.getQueryParameter("v")
        ?: u.path?.trim('/')?.split('/')?.let { seg ->
            when {
                u.host?.contains("youtu.be") == true -> seg.firstOrNull()
                seg.size >= 2 && seg[0] in setOf("shorts", "live", "embed") -> seg[1]
                else -> null
            }
        }
}

private fun youtubeThumbFor(url: String): String? = videoIdFromUrl(url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

@Composable
private fun DataSaverPill(modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.DataSaverOn, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Data saver", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AudioOnlyOverlay() {
    Box(
        Modifier.fillMaxSize().background(BrandGradient),
        contentAlignment = Alignment.Center
    ) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "eq")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(5) { i ->
                val h by t.animateFloat(
                    initialValue = 12f,
                    targetValue = 48f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        tween(420 + i * 90),
                        androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "bar$i"
                )
                Box(Modifier.width(8.dp).height(h.dp).clip(CircleShape).background(Color.White))
            }
        }
    }
}


/** YouTube-style collapsible description: 3-line preview, "…more" / "Show less". */
@Composable
private fun DescriptionCard(html: String) {
    val text = remember(html) { android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim() }
    var open by remember(html) { mutableStateOf(false) }
    var overflowing by remember(html) { mutableStateOf(false) }
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { open = !open }
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            .padding(12.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (open) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) overflowing = it.hasVisualOverflow }
        )
        if (open || overflowing) {
            Text(
                if (open) "Show less" else "…more",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** Chapter cards with preview thumbnails; the playing chapter is highlighted and kept in view. */
@Composable
private fun ChaptersCarousel(s: PlayerUiState.Success, viewModel: PlayerViewModel) {
    val chapters = s.info.chapters
    val player = viewModel.playerController.player
    var posSec by remember { mutableStateOf(0) }
    LaunchedEffect(player) { while (true) { posSec = (player.currentPosition / 1000).toInt(); delay(1000) } }
    val current = chapters.indexOfLast { it.startSeconds <= posSec }.coerceAtLeast(0)
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(current) { rowState.animateScrollToItem((current - 1).coerceAtLeast(0)) }

    Column {
        Text(
            "Chapters",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
        )
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(chapters) { i, ch ->
                val active = i == current
                Column(
                    Modifier
                        .width(168.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .bounceClick { viewModel.seekTo(ch.startSeconds) }
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                        com.youtubly.app.presentation.blur.BlurredAsyncImage(
                            model = ch.thumbnailUrl ?: s.info.video.thumbnailUrl,
                            channel = com.youtubly.app.blur.channelKeyOf(s.info.video.uploaderUrl),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            formatDuration(ch.startSeconds.toLong()),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                                .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        ch.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}


/** Opens the share sheet; playback pauses while it's open and resumes afterwards. */
@Composable
private fun SharePill(url: String, title: String, durationSec: Int, controller: com.youtubly.app.playback.PlayerController) {
    var open by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf(false) }
    ActionPill(Icons.Rounded.Share, "Share", onClick = { open = true })
    if (open) {
        val loop = controller.abLoop.value.takeIf { it.isActive }?.let { ((it.a ?: 0L) / 1000).toInt() to ((it.b ?: 0L) / 1000).toInt() }
        com.youtubly.app.presentation.components.ShareVideoSheet(
            url = url, title = title,
            positionSec = (controller.player.currentPosition / 1000).toInt(),
            durationSec = durationSec, loop = loop,
            onOpened = { resume = controller.player.isPlaying; controller.player.pause() },
            onClosed = { if (resume) controller.player.play() },
            onDismiss = { open = false },
        )
    }
}
