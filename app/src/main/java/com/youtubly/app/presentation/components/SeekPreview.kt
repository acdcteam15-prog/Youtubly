package com.youtubly.app.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import com.youtubly.app.domain.model.PreviewFrameSet

/**
 * The storyboard frame for [positionMs], cropped out of its sheet image. Sheets are loaded
 * through Coil (so they're disk-cached) and kept decoded here while the player is open.
 * Draws nothing until the sheet has arrived — the time bubble below still shows meanwhile.
 */
@Composable
fun SeekPreviewFrame(frames: PreviewFrameSet, positionMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sheets = remember(frames) { mutableStateMapOf<String, ImageBitmap>() }
    val ref = frames.frameAt(positionMs) ?: return

    LaunchedEffect(ref.url) {
        if (ref.url in sheets) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(ref.url)
            .allowHardware(false) // we crop it on a Canvas
            .build()
        val result = context.imageLoader.execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
        if (bitmap != null) sheets[ref.url] = bitmap
    }

    val sheet = sheets[ref.url] ?: return
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .aspectRatio(ref.width.toFloat() / ref.height.coerceAtLeast(1))
            .clip(shape)
            .background(Color.Black)
            .border(2.dp, Color.White, shape)
    ) {
        Canvas(Modifier.matchParentSize()) {
            // Sheets can be smaller than width×rows (the last one often is) — clamp the source.
            val srcW = ref.width.coerceAtMost(sheet.width - ref.left).coerceAtLeast(1)
            val srcH = ref.height.coerceAtMost(sheet.height - ref.top).coerceAtLeast(1)
            if (ref.left >= sheet.width || ref.top >= sheet.height) return@Canvas
            drawImage(
                image = sheet,
                srcOffset = IntOffset(ref.left, ref.top),
                srcSize = IntSize(srcW, srcH),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )
        }
    }
}
