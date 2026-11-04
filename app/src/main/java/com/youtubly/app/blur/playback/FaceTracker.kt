package com.youtubly.app.blur.playback

import android.graphics.Bitmap
import android.os.SystemClock
import com.youtubly.app.blur.BlurEngine
import com.youtubly.app.blur.BlurSettings
import com.youtubly.app.blur.BlurSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Ellipse in normalized frame coords, top-left origin. */
data class NormRegion(val cx: Float, val cy: Float, val rx: Float, val ry: Float)

/**
 * CPU side: runs detection on sampled frames and keeps a short memory of faces.
 * Detection lags ~150–300 ms behind the picture, so:
 *  - each face is held for [HOLD_MS] after it was last seen (covers motion + missed detections)
 *  - playback uses extra padding so a moving face stays inside its ellipse
 *  - if detection fails outright, the whole frame is hidden (fail-closed) until it recovers
 */
class FaceTracker(private val engine: BlurEngine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    val busy get() = running.get()

    @Volatile var channelKey: String? = null
    @Volatile var videoId: String? = null
    @Volatile private var tracked: List<Pair<NormRegion, Long>> = emptyList()
    @Volatile private var failed = false

    /** The settings for the channel being played (its own coverage / gender choices included). */
    fun settings(): BlurSettings = engine.effective(channelKey)
    fun isActive(): Boolean = engine.shouldProcess(channelKey, BlurSurface.PLAYBACK, videoId = videoId)

    fun reset() { tracked = emptyList(); failed = false }

    /** Detection can't run (GPU readback broken): hide the whole frame rather than expose faces. */
    fun markFailed() { failed = true }

    fun currentRegions(): List<NormRegion> {
        if (failed) return listOf(NormRegion(0.5f, 0.5f, 0.75f, 0.75f))   // whole frame
        val now = SystemClock.elapsedRealtime()
        return tracked.filter { now - it.second < HOLD_MS }.map { it.first }.take(FaceBlurShaderProgram.MAX_REGIONS)
    }

    /** Takes ownership of [frame] when [owned] (recycled after use); otherwise the caller keeps
     *  reusing it and must not write to it until [busy] is false again. Dropped if a detection
     *  is already running. */
    fun submit(frame: Bitmap, owned: Boolean = true) {
        if (!running.compareAndSet(false, true)) { if (owned) frame.recycle(); return }
        scope.launch {
            try {
                val (_, rects) = engine.findRegions(frame, settings(), maxSide = frame.width, extraPaddingPct = 15)
                val w = frame.width.toFloat(); val h = frame.height.toFloat()
                val now = SystemClock.elapsedRealtime()
                val fresh = rects.map {
                    NormRegion(it.exactCenterX() / w, it.exactCenterY() / h, it.width() / w / 2f, it.height() / h / 2f) to now
                }
                // keep recently seen faces that weren't re-detected this round
                val kept = tracked.filter { (r, t) -> now - t < HOLD_MS && fresh.none { overlaps(it.first, r) } }
                tracked = (fresh + kept).take(FaceBlurShaderProgram.MAX_REGIONS)
                failed = false
            } catch (t: Throwable) {
                failed = true
            } finally {
                if (owned) frame.recycle()
                running.set(false)
            }
        }
    }

    private fun overlaps(a: NormRegion, b: NormRegion) =
        kotlin.math.abs(a.cx - b.cx) < (a.rx + b.rx) * 0.6f && kotlin.math.abs(a.cy - b.cy) < (a.ry + b.ry) * 0.6f

    private companion object { const val HOLD_MS = 700L }
}
