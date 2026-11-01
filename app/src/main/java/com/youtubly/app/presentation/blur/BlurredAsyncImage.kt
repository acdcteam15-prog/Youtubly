package com.youtubly.app.presentation.blur

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.transformations
import com.youtubly.app.blur.BlurSurface
import com.youtubly.app.blur.BlurTransformation
import com.youtubly.app.blur.LocalBlurEngine

/**
 * Drop-in replacement for Coil's AsyncImage for any picture that may contain faces.
 * [model] may be a URL or an ImageRequest (its options — crossfade etc. — are kept).
 *
 *  - Blur off / channel excluded → exactly the original request (hardware bitmaps, fast path).
 *  - Active → software bitmap + BlurTransformation. The raw image is never drawn: the
 *    placeholder (usually the card's shimmer) stays until the blurred result crossfades in.
 *    Raw [error] painters and raw placeholderMemoryCacheKeys are dropped for the same reason.
 *
 * @param channel whitelist key (see channelKeyOf); null = unknown channel, always processed.
 */
@Composable
fun BlurredAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    channel: String? = null,
    placeholder: Painter? = null,
    error: Painter? = null,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    surface: BlurSurface = BlurSurface.THUMBNAIL,
    /** For the per-video "Don't blur this video" override. */
    videoId: String? = null,
) {
    val engine = LocalBlurEngine.current
    val settings by engine.settings.collectAsStateWithLifecycle()
    val modelInfo by engine.classifier.info.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val active = model != null && engine.shouldProcess(channel, surface, settings, videoId)

    val request: Any? = remember(model, active, settings, modelInfo?.id, channel, surface, videoId) {
        if (!active) model
        else {
            val base = (model as? ImageRequest)?.newBuilder() ?: ImageRequest.Builder(ctx).data(model).crossfade(250)
            // Source identity for the on-disk blur cache (URLs only; other models skip the disk cache).
            val sourceKey = ((model as? ImageRequest)?.data ?: model)?.let { it as? String ?: (it as? android.net.Uri)?.toString() }
            base.allowHardware(false)                                  // transformation needs pixel access
                .placeholderMemoryCacheKey(null as MemoryCache.Key?)   // could be the unblurred bitmap
                .transformations(BlurTransformation(engine, channel, surface, settings, modelInfo?.id ?: 0L, sourceKey))
                .build()
        }
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        placeholder = placeholder,
        error = if (active) placeholder else error,
        alignment = alignment,
        contentScale = contentScale,
    )
}

/** True when pictures from [channel] are currently being blurred (for callers with custom fallbacks). */
@Composable
fun rememberBlurActive(channel: String?, surface: BlurSurface = BlurSurface.THUMBNAIL, videoId: String? = null): Boolean {
    val engine = LocalBlurEngine.current
    val settings by engine.settings.collectAsStateWithLifecycle()
    return engine.shouldProcess(channel, surface, settings, videoId)
}
