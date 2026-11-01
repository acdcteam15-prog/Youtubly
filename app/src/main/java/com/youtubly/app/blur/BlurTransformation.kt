package com.youtubly.app.blur

import android.graphics.Bitmap
import android.graphics.Rect
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.CancellationException

/**
 * Coil 3 transformation. Coil includes cacheKey in the memory-cache key, so blurred and
 * original bitmaps never mix, and a settings/model change produces fresh results.
 * Fail-closed: if detection crashes, the whole image is blurred instead of shown raw.
 */
class BlurTransformation(
    private val engine: BlurEngine,
    private val channelId: String?,
    private val surface: BlurSurface,
    private val settings: BlurSettings,
    modelId: Long,
    /** Where the picture came from (usually its URL); enables the on-disk cache. */
    private val sourceKey: String? = null,
) : Transformation() {

    // whitelist / per-video exclusions left out of the key: excluded items never get this transformation
    override val cacheKey = "blur:${surface}:${settings.copy(whitelist = emptySet(), excludedVideos = emptySet()).hashCode()}:$modelId"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = try {
        val disk = engine.diskCache.takeIf { sourceKey != null && surface == BlurSurface.THUMBNAIL }
        disk?.get(sourceKey!!, cacheKey)
            ?: engine.process(input, channelId, surface, settings).let { out ->
                if (disk != null && out.skipped == null) disk.put(sourceKey!!, cacheKey, out.bitmap)
                out.bitmap
            }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        BlurRenderer.render(input, listOf(Rect(0, 0, input.width, input.height)), BlurMode.GAUSSIAN, 50, oval = false)
    }
}
