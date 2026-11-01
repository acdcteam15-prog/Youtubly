package com.youtubly.app.presentation.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.youtubly.app.domain.editor.TimeRange
import com.youtubly.app.domain.editor.TrimHandle
import com.youtubly.app.domain.editor.TrimRules

/** Horizontal inset so handles at 0 and at the very end stay fully visible and grabbable. */
private val EdgeInset = 14.dp
private val HandleWidth = 12.dp

/**
 * Thumbnail strip with the trim selection. Drag anywhere to move the nearer handle (so small
 * handles are easy to hit); tap to move the playhead. Only horizontal drags are claimed, so
 * vertical swipes still scroll the page.
 */
@Composable
fun TrimTimeline(
    durationMs: Long,
    trim: TimeRange,
    positionMs: Long,
    frames: List<Bitmap?>,
    onHandleDrag: (TrimHandle, Long) -> Unit,
    onHandleDragEnd: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Parts removed from inside the selection (drawn in the error colour). */
    cuts: List<TimeRange> = emptyList(),
    /** First mark of a cut that's being placed. */
    pendingCutMs: Long? = null
) {
    // Gesture coroutines outlive recompositions; read the latest values through these.
    val currentTrim by rememberUpdatedState(trim)
    val drag by rememberUpdatedState(onHandleDrag)
    val dragEnd by rememberUpdatedState(onHandleDragEnd)
    val seek by rememberUpdatedState(onSeek)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val insetPx = with(LocalDensity.current) { EdgeInset.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHighest
    val cutColor = MaterialTheme.colorScheme.error
    val safeDuration = durationMs.coerceAtLeast(1)

    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .pointerInput(safeDuration, insetPx) {
                fun toMs(x: Float): Long {
                    val span = (size.width - 2 * insetPx).coerceAtLeast(1f)
                    return (((x - insetPx) / span).coerceIn(0f, 1f) * safeDuration).toLong()
                }
                var active = TrimHandle.START
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val ms = toMs(offset.x)
                        active = TrimRules.nearestHandle(currentTrim, ms)
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        drag(active, ms)
                    },
                    onDragEnd = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); dragEnd() },
                    onDragCancel = { dragEnd() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        drag(active, toMs(change.position.x))
                    }
                )
            }
            .pointerInput(safeDuration, insetPx) {
                detectTapGestures { offset ->
                    val span = (size.width - 2 * insetPx).coerceAtLeast(1f)
                    seek((((offset.x - insetPx) / span).coerceIn(0f, 1f) * safeDuration).toLong())
                }
            }
    ) {
        Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = EdgeInset)) {
            frames.forEachIndexed { i, bmp ->
                key(i) {
                    val slot = Modifier.weight(1f).fillMaxHeight()
                    if (bmp != null) {
                        val image = remember(bmp) { bmp.asImageBitmap() }
                        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = slot)
                    } else {
                        Box(slot.padding(1.dp).background(placeholder))
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val span = (size.width - 2 * insetPx).coerceAtLeast(1f)
            fun x(ms: Long) = insetPx + (ms.coerceIn(0, safeDuration).toFloat() / safeDuration) * span
            val xs = x(trim.startMs)
            val xe = x(trim.endMs)
            val shade = Color.Black.copy(alpha = 0.6f)
            // Dim what gets cut away.
            if (xs > 0f) drawRect(shade, topLeft = Offset(0f, 0f), size = Size(xs, size.height))
            if (xe < size.width) drawRect(shade, topLeft = Offset(xe, 0f), size = Size(size.width - xe, size.height))
            // Selection frame.
            val bar = 3.dp.toPx()
            drawRect(accent, topLeft = Offset(xs, 0f), size = Size((xe - xs).coerceAtLeast(0f), bar))
            drawRect(accent, topLeft = Offset(xs, size.height - bar), size = Size((xe - xs).coerceAtLeast(0f), bar))
            // Removed parts (only where they overlap the selection).
            for (c in cuts) {
                val cs = x(maxOf(c.startMs, trim.startMs))
                val ce = x(minOf(c.endMs, trim.endMs))
                if (ce > cs) {
                    drawRect(cutColor.copy(alpha = 0.55f), topLeft = Offset(cs, 0f), size = Size(ce - cs, size.height))
                    drawRect(cutColor, topLeft = Offset(cs, size.height / 2 - 1.dp.toPx()), size = Size(ce - cs, 2.dp.toPx()))
                }
            }
            pendingCutMs?.let { ms ->
                val xm = x(ms)
                drawRect(cutColor, topLeft = Offset(xm - 1.5.dp.toPx(), 0f), size = Size(3.dp.toPx(), size.height))
            }
            // Handles.
            val hw = HandleWidth.toPx()
            val grip = Color.White.copy(alpha = 0.9f)
            for (hx in listOf(xs, xe)) {
                drawRoundRect(accent, topLeft = Offset(hx - hw / 2, 0f), size = Size(hw, size.height), cornerRadius = CornerRadius(hw / 2, hw / 2))
                drawRect(grip, topLeft = Offset(hx - 1.dp.toPx(), size.height * 0.3f), size = Size(2.dp.toPx(), size.height * 0.4f))
            }
            // Playhead.
            val xp = x(positionMs)
            drawRect(Color.White, topLeft = Offset(xp - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), size.height))
        }
    }
}
