package com.youtubly.app.presentation.components

import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/**
 * YouTube-style controls drawn in Compose over a controller-less PlayerView.
 * Replaces Media3's default controller, whose big bottom bar covered half the video.
 *
 * Tap = show/hide (auto-hides after 3 s while playing) · double-tap left/right = ∓10 s ·
 * swipe down = minimize to the in-app mini player · swipe up = fullscreen.
 */
@Composable
fun VideoControls(
    player: Player,
    fullscreen: Boolean,
    onMinimize: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onDragY: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onSettings: (() -> Unit)? = null,
    showPipButton: Boolean = true,
    chapterMarksMs: LongArray = LongArray(0),
    loopAMs: Long? = null,
    loopBMs: Long? = null,
    loopActive: Boolean = false,
    previewFrames: com.youtubly.app.domain.model.PreviewFrameSet? = null,
    /** Player sheet (Phase 7): a downward drag on the video moves the sheet itself (finger-
     *  tracking), and release settles it with the fling velocity. Null = old route behaviour. */
    onSheetDrag: ((Float) -> Unit)? = null,
    onSheetDragEnd: ((Float) -> Unit)? = null,
    /** Autoplay switch in the top bar; null hides it (nothing to autoplay into). */
    autoplay: Boolean? = null,
    onToggleAutoplay: () -> Unit = {},
    /** (start ms, title) per chapter — the title slides in when playback enters a new one. */
    chapters: List<Pair<Long, String>> = emptyList(),
    /** Hoisted lock (null = VideoControls owns it). When set, the caller draws the lock overlay
     *  itself (e.g. the offline player locks the whole screen, not only the video). */
    lockedState: Boolean? = null,
    onLockedChange: (Boolean) -> Unit = {},
    /** Current volume boost (dB) and its setter: when given, the fullscreen right-edge volume swipe
     *  continues past 100 % into boost (up to [com.youtubly.app.playback.VolumeBoost.MAX_DB] dB). */
    boostDb: Int = 0,
    onBoostChange: ((Int) -> Unit)? = null
) {
    val sheetDrag by androidx.compose.runtime.rememberUpdatedState(onSheetDrag)
    val sheetDragEnd by androidx.compose.runtime.rememberUpdatedState(onSheetDragEnd)
    val velocity = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }
    // The controls move WITH the sheet while it's dragged, so pointer positions local to this node
    // lag behind the finger (each event subtracts the previous move — the sheet crept at ~half
    // speed, needed two swipes and could stop mid-way). Track the finger in root coordinates.
    val nodeRootY = remember { floatArrayOf(0f) }
    val sheetFinger = remember { floatArrayOf(Float.NaN, 0f) } // [last root Y, travelled]
    // True while this node is moving the sheet. If the node is ever removed mid-drag, the gesture
    // dies without onDragEnd/onDragCancel, so settle the sheet here instead of leaving it parked.
    val sheetDragging = remember { booleanArrayOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { if (sheetDragging[0]) { sheetDragging[0] = false; sheetDragEnd?.invoke(0f) } }
    }
    var visible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) } // bump to restart the auto-hide timer
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var ended by remember { mutableStateOf(player.playbackState == Player.STATE_ENDED) }
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var seekFlash by remember { mutableStateOf<String?>(null) }
    // Double-tap seek: side (-1 left, +1 right, 0 idle), running total, last tap for the ripple.
    // rememberUpdatedState: the tap detector is keyed on the player only, so it must read the live value.
    val seekStepMs by androidx.compose.runtime.rememberUpdatedState(LocalSeekStepSeconds.current.value.coerceAtLeast(1) * 1000L)
    var seekSide by remember { mutableIntStateOf(0) }
    var seekTotalMs by remember { mutableLongStateOf(0L) }
    var seekTaps by remember { mutableIntStateOf(0) }            // bump per tap → restarts clear timer + ripple
    var seekTapPoint by remember { mutableStateOf(Offset.Zero) }
    var lastSeekPressAt by remember { mutableLongStateOf(0L) }
    // Hold-to-2× state
    var boosted by remember { mutableStateOf(false) }
    // Controls lock: every touch on the video is ignored until unlocked (kids, lying down…).
    var lockedInternal by remember(player) { mutableStateOf(false) }
    val lockedNow by androidx.compose.runtime.rememberUpdatedState(lockedState)
    val lockCb by androidx.compose.runtime.rememberUpdatedState(onLockedChange)
    val locked = lockedState ?: lockedInternal
    fun setLocked(v: Boolean) { if (lockedNow != null) lockCb(v) else lockedInternal = v }
    var showUnlock by remember { mutableStateOf(false) }
    LaunchedEffect(showUnlock) { if (showUnlock) { delay(2500); showUnlock = false } }
    var speedBeforeBoost by remember { mutableFloatStateOf(1f) }
    // Fullscreen edge swipes: brightness (left) / volume (right)
    var gestureMode by remember { mutableIntStateOf(0) } // 0 drag-to-minimize, 1 brightness, 2 volume
    var levelHud by remember { mutableStateOf<Pair<Int, Float>?>(null) } // (mode, 0..1)
    val context = androidx.compose.ui.platform.LocalContext.current
    val audio = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val window = (context as? android.app.Activity)?.window
    var brightness by remember {
        mutableFloatStateOf(
            window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                ?: (android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
        )
    }
    var volumeF by remember { mutableFloatStateOf(0f) }
    val boostCb by androidx.compose.runtime.rememberUpdatedState(onBoostChange)
    val boostNow by androidx.compose.runtime.rememberUpdatedState(boostDb)
    // Brightness override only lives in fullscreen; hold-to-2× never outlives the screen.
    LaunchedEffect(fullscreen) {
        if (!fullscreen) window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (boosted) player.setPlaybackSpeed(speedBeforeBoost)
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } }
        }
    }
    LaunchedEffect(levelHud) { if (levelHud != null) { delay(900); levelHud = null } }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                isPlaying = p.isPlaying
                buffering = p.playbackState == Player.STATE_BUFFERING
                ended = p.playbackState == Player.STATE_ENDED
                duration = p.duration.coerceAtLeast(0)
                position = p.currentPosition
                if (ended) visible = true
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            if (scrubFraction == null) position = player.currentPosition
            delay(250)
        }
    }
    LaunchedEffect(visible, isPlaying, interactions, scrubFraction) {
        if (visible && isPlaying && scrubFraction == null) { delay(3000); visible = false }
    }
    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(600); seekFlash = null } }
    // Kept after the flash clears so the exit fade still has text to show.
    var lastFlash by remember { mutableStateOf("") }
    var seekFlashCount by remember { mutableStateOf(0) }

    // Speed bubble: react to speed changes from anywhere (settings sheet, per-channel speed…).
    var speedFlash by remember { mutableStateOf<String?>(null) }
    var lastSpeed by remember { mutableStateOf("") }
    DisposableEffect(player) {
        var prev = player.playbackParameters.speed
        val l = object : Player.Listener {
            override fun onPlaybackParametersChanged(p: androidx.media3.common.PlaybackParameters) {
                if (p.speed == prev) return
                prev = p.speed
                val t = if (p.speed == p.speed.toInt().toFloat()) "${p.speed.toInt()}×" else "${p.speed}×"
                lastSpeed = t; speedFlash = t + System.nanoTime() // unique so repeats re-trigger
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(speedFlash) { if (speedFlash != null) { delay(900); speedFlash = null } }

    // Chapter title on chapter change (not when the video first opens).
    val chapterIndex = remember(chapters, position) { chapters.indexOfLast { it.first <= position } }
    var chapterFlash by remember { mutableStateOf<String?>(null) }
    var lastChapter by remember { mutableStateOf("") }
    var seenChapter by remember(chapters) { mutableStateOf<Int?>(null) }
    LaunchedEffect(chapterIndex) {
        val prev = seenChapter
        seenChapter = chapterIndex
        if (prev == null || chapterIndex < 0 || chapterIndex == prev || scrubFraction != null) return@LaunchedEffect
        lastChapter = chapters[chapterIndex].second
        chapterFlash = lastChapter
        delay(2200)
        chapterFlash = null
    }
    // Clear the ripple ~0.7 s after the last tap; until then each extra tap on a side adds a step.
    LaunchedEffect(seekTaps) { if (seekTaps > 0) { delay(700); seekSide = 0; seekTotalMs = 0L } }

    fun seekBy(ms: Long) {
        val target = (player.currentPosition + ms).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        player.seekTo(target); position = target
        seekFlash = if (ms > 0) "+${ms / 1000}s" else "${ms / 1000}s"
        lastFlash = seekFlash.orEmpty(); seekFlashCount++
        interactions++
    }
    /** One double-tap step on [side] (-1 back, +1 forward). Switching sides restarts the total. */
    fun stepSeek(side: Int, at: Offset) {
        if (side != seekSide) { seekSide = side; seekTotalMs = 0L }
        val delta = side * seekStepMs
        val target = (player.currentPosition + delta).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        player.seekTo(target); position = target
        seekTotalMs += seekStepMs
        seekTapPoint = at
        seekTaps++
        visible = false // keep the video clear, like YouTube
    }
    fun togglePlay() {
        when {
            ended -> { player.seekTo(0); player.play() }
            player.isPlaying -> player.pause()
            // After an error (e.g. network switched Wi-Fi -> mobile data while away) the player
            // sits in IDLE and play() alone does nothing. prepare() re-opens it at the same spot.
            player.playbackState == androidx.media3.common.Player.STATE_IDLE -> { player.prepare(); player.play() }
            else -> player.play()
        }
        interactions++
    }

    val density = LocalDensity.current
    val threshold = with(density) { 90.dp.toPx() }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val scrim by animateFloatAsState(if (visible) 0.45f else 0f, label = "scrim")

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = scrim))
            .pointerInput(player) {
                // Gesture map (checked for clashes):
                //  • tap = show/hide controls   • hold = 2× (onLongPress, unaffected)
                //  • double-tap left/right third = seek; while the ripple is up, every further
                //    single tap on a side adds another step (handled in onPress, so it's instant)
                //  • double-tap middle third = show/hide controls
                //  • vertical drags (minimise / brightness / volume) live in a separate detector.
                detectTapGestures(
                    onPress = { o ->
                        val third = when {
                            o.x < size.width / 3f -> -1
                            o.x > size.width * 2f / 3f -> 1
                            else -> 0
                        }
                        if (seekSide != 0 && third != 0) {
                            stepSeek(third, o)
                            lastSeekPressAt = System.currentTimeMillis()
                        }
                        tryAwaitRelease()
                        if (boosted) { player.setPlaybackSpeed(speedBeforeBoost); boosted = false }
                    },
                    onLongPress = {
                        if (player.isPlaying) {
                            speedBeforeBoost = player.playbackParameters.speed
                            player.setPlaybackSpeed(2f)
                            boosted = true
                            visible = false
                        }
                    },
                    onTap = {
                        // A tap already consumed as a seek step must not also toggle the controls.
                        if (System.currentTimeMillis() - lastSeekPressAt < 1_000) return@detectTapGestures
                        visible = !visible; interactions++
                    },
                    onDoubleTap = { o ->
                        if (System.currentTimeMillis() - lastSeekPressAt < 1_000) return@detectTapGestures
                        when {
                            o.x < size.width / 3f -> stepSeek(-1, o)
                            o.x > size.width * 2f / 3f -> stepSeek(1, o)
                            else -> { visible = !visible; interactions++ }
                        }
                    }
                )
            }
            .onGloballyPositioned { nodeRootY[0] = it.positionInRoot().y }
            .pointerInput(fullscreen) {
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        velocity.resetTracking()
                        sheetFinger[0] = o.y + nodeRootY[0]; sheetFinger[1] = 0f
                        gestureMode = when {
                            !fullscreen -> 0
                            o.x < size.width * 0.35f -> 1
                            o.x > size.width * 0.65f -> 2
                            else -> 0
                        }
                        if (gestureMode == 2) {
                            // 0..1 = device volume, 1..2 = boost (1 + dB / max). Start where things are now.
                            volumeF = if (boostCb != null && boostNow > 0) 1f + boostNow.toFloat() / com.youtubly.app.playback.VolumeBoost.MAX_DB
                            else audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() /
                                audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                        }
                    },
                    onDragEnd = {
                        sheetDragging[0] = false
                        val end = sheetDragEnd
                        if (gestureMode == 0) when {
                            end != null && !fullscreen -> {
                                // Swipe up still means fullscreen; anything else lets the sheet snap
                                // open or closed from where the finger left it (fling-aware).
                                if (dragAccum < -threshold) onToggleFullscreen()
                                end(if (dragAccum < 0f) 0f else velocity.calculateVelocity().y)
                            }
                            dragAccum > threshold -> if (fullscreen) onToggleFullscreen() else onMinimize()
                            dragAccum < -threshold && !fullscreen -> onToggleFullscreen()
                        }
                        dragAccum = 0f; onDragY(0f)
                    },
                    onDragCancel = { sheetDragging[0] = false; if (gestureMode == 0 && !fullscreen) sheetDragEnd?.invoke(0f); dragAccum = 0f; onDragY(0f) }
                ) { change, dy ->
                    change.consume()
                    val delta = -dy / (size.height * 0.8f) // full swipe ≈ 0→100 %
                    when (gestureMode) {
                        1 -> {
                            brightness = (brightness + delta).coerceIn(0.01f, 1f)
                            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
                            levelHud = 1 to brightness
                        }
                        2 -> {
                            val cb = boostCb
                            val maxV = if (cb != null) 2f else 1f
                            volumeF = (volumeF + delta * (if (cb != null) 1.5f else 1f)).coerceIn(0f, maxV)
                            val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            if (volumeF >= 1f && cb != null) {
                                // Past the top of the device volume: pin it at max and add boost.
                                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, max, 0)
                                val db = ((volumeF - 1f) * com.youtubly.app.playback.VolumeBoost.MAX_DB).roundToIntSafe()
                                if (db != boostNow) cb(db)
                                levelHud = 3 to (volumeF - 1f)
                            } else {
                                if (cb != null && boostNow != 0) cb(0)
                                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (volumeF * max).roundToIntSafe(), 0)
                                levelHud = 2 to volumeF
                            }
                        }
                        else -> {
                            val sd = sheetDrag
                            if (sd != null && !fullscreen) {
                                // Finger position on screen = local position + where this node was laid out.
                                val rootY = change.position.y + nodeRootY[0]
                                val screenDy = if (sheetFinger[0].isNaN()) dy else rootY - sheetFinger[0]
                                sheetFinger[0] = rootY
                                sheetFinger[1] += screenDy
                                dragAccum += screenDy
                                velocity.addPosition(change.uptimeMillis, androidx.compose.ui.geometry.Offset(0f, sheetFinger[1]))
                                sheetDragging[0] = true
                                sd(screenDy)
                            } else {
                                dragAccum += dy
                                onDragY(if (fullscreen) 0f else dragAccum.coerceAtLeast(0f))
                            }
                        }
                    }
                }
            }
    ) {
        if (seekSide != 0) {
            SeekRipple(
                side = seekSide,
                totalSeconds = (seekTotalMs / 1000).toInt(),
                tapKey = seekTaps,
                tapPoint = seekTapPoint,
                modifier = Modifier
            )
        }
        // Seek buttons: "+10s" pill that pops on each press.
        AnimatedVisibility(
            seekFlash != null,
            enter = androidx.compose.animation.scaleIn(initialScale = 0.7f) + fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center).padding(bottom = 110.dp)
        ) {
            Text(
                lastFlash, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier
                    .popOnChange(seekFlashCount, peak = 1.15f)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        // Speed changes: "1.5×" bubble in the middle of the video.
        AnimatedVisibility(
            speedFlash != null && !boosted,
            enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.6f), initialScale = 0.6f) + fadeIn(),
            exit = androidx.compose.animation.scaleOut(targetScale = 1.1f) + fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                lastSpeed, color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
        // Entering a new chapter: its name slides in above the seek bar for a moment.
        AnimatedVisibility(
            chapterFlash != null,
            enter = androidx.compose.animation.slideInHorizontally { -it / 3 } + fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally { -it / 3 } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 72.dp, bottom = if (fullscreen) 96.dp else 70.dp)
        ) {
            Text(
                lastChapter, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        if (boosted) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("2×", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.padding(start = 4.dp).size(18.dp))
            }
        }
        levelHud?.let { (mode, v) ->
            Row(
                Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (mode == 1) Icons.Rounded.BrightnessMedium else Icons.Rounded.VolumeUp, null, tint = if (mode == 3) MaterialTheme.colorScheme.primary else Color.White)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(110.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth(v.coerceIn(0f, 1f)).height(4.dp).background(if (mode == 3) MaterialTheme.colorScheme.primary else Color.White))
                }
                Text(
                    if (mode == 3) "Boost +${(v.coerceIn(0f, 1f) * com.youtubly.app.playback.VolumeBoost.MAX_DB).roundToIntSafe()} dB" else "${(v * 100).toInt()}%",
                    color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
        if (buffering && !visible) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(40.dp))
        }

        AnimatedVisibility(visible && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top row
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    CtlButton(Icons.Rounded.KeyboardArrowDown, "Minimize", onClick = onMinimize)
                    Spacer(Modifier.weight(1f))
                    CtlButton(Icons.Rounded.Lock, "Lock controls") { setLocked(true); visible = false; showUnlock = true }
                    autoplay?.let { on -> AutoplaySwitch(on) { onToggleAutoplay(); interactions++ } }
                    if (showPipButton && !fullscreen) CtlButton(Icons.Rounded.PictureInPictureAlt, "Mini player", onClick = onMinimize)
                    onSettings?.let { CtlButton(Icons.Rounded.Settings, "Playback settings") { it(); interactions++ } }
                }
                // Centre transport
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CtlButton(Icons.Rounded.Replay10, "Back 10 seconds", size = 44) { seekBy(-10_000) }
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (buffering) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                        else IconButton(onClick = ::togglePlay, modifier = Modifier.size(64.dp)) {
                            // Icon morph: the old glyph shrinks/spins out while the new one springs in.
                            val glyph = when { ended -> Icons.Rounded.Replay; isPlaying -> Icons.Rounded.Pause; else -> Icons.Rounded.PlayArrow }
                            androidx.compose.animation.AnimatedContent(
                                targetState = glyph,
                                transitionSpec = {
                                    (androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 700f), initialScale = 0.4f) +
                                        fadeIn(androidx.compose.animation.core.tween(120))) togetherWith
                                        (androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(120), targetScale = 0.4f) + fadeOut(androidx.compose.animation.core.tween(100)))
                                },
                                label = "playPause"
                            ) { g ->
                                Icon(g, "Play/pause", tint = Color.White, modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                    CtlButton(Icons.Rounded.Forward10, "Forward 10 seconds", size = 44) { seekBy(10_000) }
                }
                // Bottom: times + fullscreen, then a slim seek bar
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val shown = scrubFraction?.let { (it * duration).toLong() } ?: position
                        Text(formatDuration(shown / 1000), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("  /  ${formatDuration(duration / 1000)}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        CtlButton(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Fullscreen", size = 26, onClick = onToggleFullscreen)
                    }
                    scrubFraction?.let { f ->
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                            val bubbleW = 64.dp
                            val x = (maxWidth * f - bubbleW / 2).coerceIn(0.dp, maxWidth - bubbleW)
                            // Storyboard frame centred over the time bubble (same clamping).
                            if (previewFrames != null && duration > 0) {
                                val frameW = if (fullscreen) 200.dp else 150.dp
                                val fx = (maxWidth * f - frameW / 2).coerceIn(0.dp, (maxWidth - frameW).coerceAtLeast(0.dp))
                                SeekPreviewFrame(
                                    frames = previewFrames,
                                    positionMs = (f * duration).toLong(),
                                    modifier = Modifier.offset(x = fx).width(frameW)
                                        .layout { m, c -> val p = m.measure(c); layout(p.width, p.height) { p.place(0, -p.height - 6.dp.roundToPx()) } }
                                )
                            }
                            Text(
                                formatDuration((f * duration).toLong() / 1000),
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.offset(x = x).width(bubbleW)
                                    .clip(RoundedCornerShape(8.dp)).background(Color.White)
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                    SeekBar(
                        fraction = scrubFraction ?: if (duration > 0) position.toFloat() / duration else 0f,
                        buffered = if (duration > 0) player.bufferedPosition.toFloat() / duration else 0f,
                        marks = if (duration > 0) chapterMarksMs.map { it.toFloat() / duration } else emptyList(),
                        loopA = if (duration > 0) loopAMs?.let { it.toFloat() / duration } else null,
                        loopB = if (duration > 0) loopBMs?.let { it.toFloat() / duration } else null,
                        loopActive = loopActive,
                        onScrub = { scrubFraction = it; interactions++ },
                        onScrubEnd = { f -> player.seekTo((f * duration).toLong()); position = (f * duration).toLong(); scrubFraction = null }
                    )
                }
            }
        }
        // Always-visible hairline progress when controls are hidden (like YouTube).
        if (!visible && duration > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).height(2.dp).background(MaterialTheme.colorScheme.primary))
            }
        }

        // ---- Locked: swallow every touch; double-tap unlocks (same as everywhere else) ----
        if (locked && lockedState == null) {
            FullScreenTouchLock(onUnlock = { setLocked(false); visible = true; interactions++ })
        }
    }
}

@Composable
private fun CtlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, size: Int = 24, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size((size + 20).dp)) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(size.dp))
    }
}

/** YouTube-style autoplay switch: a small track with a play/pause knob. */
@Composable
private fun AutoplaySwitch(on: Boolean, onClick: () -> Unit) {
    val knobX by animateFloatAsState(if (on) 1f else 0f, label = "autoplayKnob")
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .size(width = 40.dp, height = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .semantics { contentDescription = if (on) "Autoplay is on" else "Autoplay is off"; role = Role.Switch },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(CircleShape)
                .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.35f))
        )
        Box(
            Modifier
                .offset(x = (knobX * 18).dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (on) Color.White else Color(0xFFBDBDBD)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (on) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                null,
                tint = Color.Black,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun SeekBar(
    fraction: Float,
    buffered: Float,
    marks: List<Float>,
    loopA: Float? = null,
    loopB: Float? = null,
    loopActive: Boolean = false,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit
) {
    var width by remember { mutableFloatStateOf(1f) }
    var last by remember { mutableFloatStateOf(0f) }
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(horizontal = 12.dp)
            .pointerInput(Unit) {
                width = size.width.toFloat()
                detectTapGestures { o -> val f = (o.x / size.width).coerceIn(0f, 1f); onScrub(f); onScrubEnd(f) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> last = (o.x / size.width).coerceIn(0f, 1f); onScrub(last) },
                    onDragEnd = { onScrubEnd(last) },
                    onDragCancel = { onScrubEnd(last) }
                ) { change, _ ->
                    change.consume()
                    last = (change.position.x / size.width).coerceIn(0f, 1f); onScrub(last)
                }
            }
    ) {
        val cy = size.height / 2f
        val h = 3.dp.toPx()
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, cy - h / 2), Size(size.width, h), r)
        drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, cy - h / 2), Size(size.width * buffered.coerceIn(0f, 1f), h), r)
        drawRoundRect(accent, Offset(0f, cy - h / 2), Size(size.width * fraction.coerceIn(0f, 1f), h), r)
        marks.forEach { m -> drawRect(Color.Black.copy(alpha = 0.6f), Offset(size.width * m - 1.dp.toPx(), cy - h / 2), Size(2.dp.toPx(), h)) }
        // A–B loop: shaded span between the points, plus a tall tick at each one.
        if (loopA != null && loopB != null && loopB > loopA) {
            val a = loopA.coerceIn(0f, 1f); val b = loopB.coerceIn(0f, 1f)
            drawRect(
                LoopColor.copy(alpha = if (loopActive) 0.55f else 0.25f),
                Offset(size.width * a, cy - h), Size(size.width * (b - a), h * 2)
            )
        }
        val tickH = 12.dp.toPx(); val tickW = 2.dp.toPx()
        listOfNotNull(loopA, loopB).forEach { m ->
            drawRect(LoopColor, Offset(size.width * m.coerceIn(0f, 1f) - tickW / 2, cy - tickH / 2), Size(tickW, tickH))
        }
        drawCircle(accent, 6.dp.toPx(), Offset(size.width * fraction.coerceIn(0f, 1f), cy))
    }
}

private val LoopColor = Color(0xFF4FC3F7)

/**
 * YouTube-style double-tap feedback: a half-circle hugging the tapped edge with the running
 * total, plus an expanding circle from each tap point.
 */
@Composable
private fun SeekRipple(side: Int, totalSeconds: Int, tapKey: Int, tapPoint: Offset, modifier: Modifier) {
    val pulse = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(tapKey) {
        pulse.snapTo(0f)
        pulse.animateTo(1f, androidx.compose.animation.core.tween(450))
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val full = constraints.maxWidth.toFloat()
        val w = maxWidth * 0.4f
        val shape = if (side < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                    else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
        val density = LocalDensity.current
        val halfPx = with(density) { w.toPx() }
        Box(
            Modifier
                .align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .width(w)
                .fillMaxHeight()
                .clip(shape)
                .background(Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            // Tap ripple, positioned relative to this half.
            Canvas(Modifier.fillMaxSize()) {
                val localX = if (side < 0) tapPoint.x else tapPoint.x - (full - halfPx)
                val p = pulse.value
                if (p < 1f) drawCircle(
                    Color.White.copy(alpha = 0.22f * (1f - p)),
                    radius = size.maxDimension * 0.6f * p,
                    center = Offset(localX, tapPoint.y)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (side < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
                    null, tint = Color.White, modifier = Modifier.size(30.dp)
                )
                Text(
                    "${if (side < 0) "−" else "+"}$totalSeconds s",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp
                )
            }
        }
    }
}

private fun Float.roundToIntSafe(): Int = kotlin.math.round(this).toInt()


/**
 * The app's one touch lock (online player, offline player, in-video lock). Swallows every touch on
 * the whole screen (taps, drags, flings, pinches) so nothing under it scrolls, seeks or opens.
 * A single tap only shows the hint; a DOUBLE-TAP anywhere unlocks.
 */
@Composable
fun FullScreenTouchLock(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(hint) { if (hint) { delay(2000); hint = false } }
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { hint = true },
                    onDoubleTap = { onUnlock() },
                    onLongPress = { hint = true }
                )
            }
            // Drags/flings/pinches: consume so nothing underneath scrolls, seeks or minimises.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                        e.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        AnimatedVisibility(
            hint,
            enter = fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.LockOpen, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Double-tap to unlock", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        if (!hint) Icon(
            Icons.Rounded.Lock, "Screen locked", tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(18.dp)
        )
    }
}
