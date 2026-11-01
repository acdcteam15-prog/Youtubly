package com.youtubly.app.presentation.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.youtubly.app.domain.model.Video
import com.youtubly.app.playback.PipState
import com.youtubly.app.playback.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class SheetValue { Expanded, Collapsed }

/** What the sheet should show. [nonce] makes re-opening the same URL still count as a request. */
data class PlayRequest(val url: String, val startMs: Long?, val nonce: Long)

/** Height of the collapsed mini bar (a 16:9 video at 114 dp wide). */
val MiniBarHeight: Dp = 64.dp

/**
 * State of the app-wide player sheet (Phase 7). The player is no longer a navigation
 * destination: anything that opens a video calls [open], and the sheet is dragged between
 * [SheetValue.Expanded] (full player) and [SheetValue.Collapsed] (mini bar).
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
class PlayerSheetState internal constructor(density: Density, private val scope: CoroutineScope) {
    var request by mutableStateOf<PlayRequest?>(null)
        private set

    val drag = AnchoredDraggableState(
        initialValue = SheetValue.Expanded,
        // A quarter of the way, or a normal flick, is enough to minimise / restore.
        positionalThreshold = { distance -> distance * 0.25f },
        velocityThreshold = { with(density) { 300.dp.toPx() } },
        snapAnimationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        decayAnimationSpec = exponentialDecay()
    )

    /** Distance (px) between the expanded and collapsed positions; set by the layout. */
    internal var maxOffset by mutableFloatStateOf(0f)

    /** Slide-up when the sheet first appears (1 = fully below the screen). */
    internal val enter = Animatable(0f)

    /** 0 = expanded, 1 = collapsed. */
    val collapseFraction: Float
        get() {
            val o = drag.offset
            return if (o.isNaN() || maxOffset <= 0f) (if (drag.currentValue == SheetValue.Collapsed) 1f else 0f)
            else (o / maxOffset).coerceIn(0f, 1f)
        }

    /** 0 when there's no sheet or it's collapsed, 1 when fully expanded — for fading the nav bar. */
    val expandedFraction: Float get() = if (request == null) 0f else (1f - collapseFraction) * (1f - enter.value)

    val isExpanded: Boolean get() = request != null && drag.targetValue == SheetValue.Expanded

    fun open(url: String, startMs: Long? = null) {
        val wasHidden = request == null
        request = PlayRequest(url, startMs, System.nanoTime())
        scope.launch {
            if (wasHidden) {
                drag.snapTo(SheetValue.Expanded)
                enter.snapTo(1f)
                enter.animateTo(0f, tween(320))
            } else {
                drag.animateTo(SheetValue.Expanded)
            }
        }
    }

    fun open(video: Video) = open(video.url)
    fun collapse() { if (request != null) scope.launch { drag.animateTo(SheetValue.Collapsed) } }
    fun expand() { if (request != null) scope.launch { drag.animateTo(SheetValue.Expanded) } }
    fun dismiss() { request = null }

    internal fun dragBy(dy: Float) { drag.dispatchRawDelta(dy) }
    internal fun settle(velocity: Float) {
        scope.launch {
            // Settle can be interrupted (anchors updated as the nav bar fades in, a new drag…).
            // Never leave the sheet parked between the two positions.
            runCatching { drag.settle(velocity) }
            if (!isAtAnchor()) drag.animateTo(drag.targetValue)
        }
    }

    private fun isAtAnchor(): Boolean {
        val o = drag.offset
        return !o.isNaN() && (kotlin.math.abs(o) < 1f || kotlin.math.abs(o - maxOffset) < 1f)
    }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember { PlayerSheetState(density, scope) }
}

/**
 * The player layer drawn over the whole app. Expanded it fills the screen; collapsed it's a
 * [MiniBarHeight] bar sitting [bottomInset] above the bottom (on top of the nav bar on tab
 * screens). The same [PlayerScreen] — and so the same video surface — is used for both, so
 * collapsing never re-attaches the surface (no black flash).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerSheet(
    state: PlayerSheetState,
    controller: PlayerController,
    bottomInset: Dp,
    onChannelClick: (String) -> Unit
) {
    val req = state.request ?: return
    val inPip by PipState.inPip

    // Playback stopped elsewhere (PiP window closed, a download started playing): close the sheet.
    LaunchedEffect(state, controller) {
        var previous = controller.nowPlayingId.value
        controller.nowPlayingId.collect { id ->
            if (previous != null && id == null) state.dismiss()
            previous = id
        }
    }

    // Back collapses an expanded sheet; the fullscreen handler inside PlayerScreen is composed
    // later, so it still wins while fullscreen. Collapsed: Back goes to the app as usual.
    BackHandler(enabled = state.isExpanded && !inPip) { state.collapse() }

    val navInset = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullH = constraints.maxHeight.toFloat()
        val miniPx = with(density) { MiniBarHeight.toPx() }
        val bottomPx = with(density) { (bottomInset + navInset).toPx() }
        val maxOffset = (fullH - miniPx - bottomPx).coerceAtLeast(1f)
        LaunchedEffect(maxOffset) {
            state.maxOffset = maxOffset
            state.drag.updateAnchors(
                DraggableAnchors {
                    SheetValue.Expanded at 0f
                    SheetValue.Collapsed at maxOffset
                },
                state.drag.targetValue
            )
        }

        androidx.compose.foundation.layout.Box(
            Modifier
                // Top edge follows the drag…
                .offset { IntOffset(0, if (inPip) 0 else (state.collapseFraction * maxOffset).toInt()) }
                // …and the height shrinks from full screen to the mini bar. The node's own size is
                // the visible part only, so a collapsed sheet never swallows touches meant for the
                // screen (or nav bar) around it.
                .layout { measurable, constraints ->
                    val p = if (inPip) 0f else state.collapseFraction
                    val h = if (inPip) fullH else fullH - p * (fullH - miniPx)
                    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, h.toInt().coerceAtLeast(1)))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .graphicsLayer { translationY = state.enter.value * fullH }
                .then(if (!inPip) Modifier.shadow(if (state.collapseFraction > 0.5f) 8.dp else 0.dp) else Modifier)
                .clipToBounds()
                .anchoredDraggable(state.drag, Orientation.Vertical, enabled = !inPip)
                .then(
                    if (state.drag.currentValue == SheetValue.Collapsed && !inPip)
                        Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { state.expand() }
                    else Modifier
                )
        ) {
            PlayerScreen(
                videoUrl = req.url,
                startMs = req.startMs,
                requestNonce = req.nonce,
                collapseProgress = { if (inPip) 0f else state.collapseFraction },
                onBack = state::collapse,
                onExpand = state::expand,
                onClose = {
                    controller.stop()
                    state.dismiss()
                },
                onSheetDrag = state::dragBy,
                onSheetDragEnd = state::settle,
                onVideoClick = { state.open(it.url) },
                onChannelClick = { url -> state.collapse(); onChannelClick(url) }
            )
        }
    }
}
