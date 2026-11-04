package com.youtubly.app.blur

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

data class BlurOutcome(val bitmap: Bitmap, val facesFound: Int, val facesBlurred: Int, val timeMs: Long, val skipped: String? = null)

class BlurEngine(
    private val detector: FaceDetector,
    val classifier: GenderClassifier,
    val store: BlurPreferencesStore,
    appScope: CoroutineScope,
    val diskCache: BlurDiskCache? = null,
    private val segmenter: PersonSegmenter? = null,
) {
    val settings: StateFlow<BlurSettings> =
        store.settings.stateIn(appScope, SharingStarted.Eagerly, BlurSettings())

    /** null = still loading (keep splash), true = show first-launch model setup, false = app. */
    val setupNeeded: StateFlow<Boolean?> = combine(store.setupDone, classifier.info) { done, info -> !done && info == null }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /** Caps parallel thumbnail work so fast scrolling doesn't spike CPU; cancelled rows free their slot. */
    private val gate = Semaphore(2)

    /** Cheap early-exit — call before anything expensive. */
    fun shouldProcess(channelKey: String?, surface: BlurSurface, sIn: BlurSettings = settings.value, videoId: String? = null): Boolean =
        shouldProcessWith(sIn.forChannel(channelKey), channelKey, surface, videoId)

    /** The settings as they apply to [channelKey] (its own coverage / gender / always-on choices on top of the general ones). */
    fun effective(channelKey: String?, s: BlurSettings = settings.value): BlurSettings = s.forChannel(channelKey)

    private fun shouldProcessWith(s: BlurSettings, channelKey: String?, surface: BlurSurface, videoId: String?): Boolean = when {
        surface == BlurSurface.PREVIEW -> s.needsDetection
        !s.enabled || !s.needsDetection -> false
        channelKey != null && channelKey in s.whitelist -> false
        videoId != null && videoId in s.excludedVideos -> false
        surface == BlurSurface.THUMBNAIL -> s.applyToThumbnails
        surface == BlurSurface.PLAYBACK -> s.applyToPlayback
        else -> true
    }

    /** Thumbnails / preview: detect + render. Detection errors propagate so callers fail closed. */
    suspend fun process(src: Bitmap, channelKey: String?, surface: BlurSurface, sIn: BlurSettings = settings.value): BlurOutcome =
        gate.withPermit {
            withContext(Dispatchers.Default) {
                val s = sIn.forChannel(channelKey)     // this channel's own choices, if it has any
                val t0 = SystemClock.elapsedRealtime()
                if (!shouldProcess(channelKey, surface, s)) return@withContext BlurOutcome(src, 0, 0, 0, "skipped")
                if (src.width < 48 || src.height < 48) return@withContext BlurOutcome(src, 0, 0, 0, "too small")
                val (found, regions) = findRegions(src, s, maxSide = 480)
                val bodyShape = s.bodyCoverage != BodyCoverage.FACE
                BlurOutcome(
                    BlurRenderer.render(src, regions, s.mode, s.strength, oval = !bodyShape, rounded = bodyShape, scaleWithSize = s.scaleWithFaceSize),
                    found, regions.size, SystemClock.elapsedRealtime() - t0
                )
            }
        }

    /**
     * Faces to hide in [src] coordinates, padding applied. Shared by thumbnails and playback.
     * @return (faces detected, regions to blur)
     */
    suspend fun findRegions(src: Bitmap, s: BlurSettings, maxSide: Int, extraPaddingPct: Int = 0): Pair<Int, List<Rect>> {
        val scale = maxSide.toFloat() / max(src.width, src.height)
        val work = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true) else src
        val k = if (work === src) 1f else 1f / scale
        try {
            val faces = detector.detect(work, s.minFaceSizePct)
            val pad = s.facePaddingPct + extraPaddingPct
            val regions = faces.filter { decide(work, it, s) }.map { f ->
                if (s.bodyCoverage == BodyCoverage.FACE) {
                    val px = f.width() * pad / 100; val py = f.height() * pad / 100
                    Rect(((f.left - px) * k).toInt(), ((f.top - py * 1.3f) * k).toInt(),     // extra top padding covers hair
                        ((f.right + px) * k).toInt(), ((f.bottom + py) * k).toInt())
                } else {
                    // Whole person: estimate the body from the face, with a small margin (a quarter of the face padding,
                    // plus a third of the extra motion padding used during playback).
                    val grow = s.facePaddingPct / 4 + extraPaddingPct / 3
                    val b = BlurMath.bodyBox(f.left, f.top, f.right, f.bottom, work.width, work.height, s.bodyCoverage, grow)
                    Rect((b[0] * k).toInt(), (b[1] * k).toInt(), (b[2] * k).toInt(), (b[3] * k).toInt())
                }
            }
            // People seen from behind have no face to find: add person-shaped blobs that contain no face.
            val behind = if (s.coverBackViews && s.bodyCoverage != BodyCoverage.FACE) backViewRegions(work, faces, s, extraPaddingPct, k) else emptyList()
            return faces.size to (regions + behind)
        } finally {
            if (work !== src) work.recycle()
        }
    }

    /**
     * Person-shaped blobs without a face, in [src] coordinates. A person of unknown gender follows "Blur uncertain faces"
     * when only one gender is selected, and is always hidden when both are. Best effort: if segmentation fails the
     * face-based regions still apply.
     */
    private suspend fun backViewRegions(work: Bitmap, faces: List<Rect>, s: BlurSettings, extraPaddingPct: Int, k: Float): List<Rect> {
        val seg = segmenter ?: return emptyList()
        if (s.needsClassification && !s.blurUncertain) return emptyList()
        val mask = try { seg.mask(work) } catch (_: Throwable) { return emptyList() }
        // The mask is as large as the picture it was computed from, but map it explicitly to be safe.
        val sx = work.width.toFloat() / mask.width
        val sy = work.height.toFloat() / mask.height
        val faceBoxes = faces.map { intArrayOf((it.left / sx).toInt(), (it.top / sy).toInt(), (it.right / sx).toInt(), (it.bottom / sy).toInt()) }
        val grow = s.facePaddingPct / 4 + extraPaddingPct / 3
        return PersonShapes.withoutFace(PersonShapes.blobs(mask), faceBoxes).map { blob ->
            val r = PersonShapes.regionFor(blob, s.bodyCoverage, mask.width, mask.height, grow)
            Rect((r[0] * sx * k).toInt(), (r[1] * sy * k).toInt(), (r[2] * sx * k).toInt(), (r[3] * sy * k).toInt())
        }
    }

    private fun decide(img: Bitmap, face: Rect, s: BlurSettings): Boolean {
        if (!s.needsClassification) return true                 // both genders selected → every face
        val r = Rect(face)
        if (!r.intersect(0, 0, img.width, img.height) || r.width() < 8 || r.height() < 8) return s.blurUncertain
        val crop = Bitmap.createBitmap(img, r.left, r.top, r.width(), r.height())
        val g = try { classifier.classify(crop, s) } catch (_: Throwable) { null } finally { crop.recycle() }
        return when {
            g == null || g.confidence < s.genderConfidence -> s.blurUncertain
            g.gender == Gender.FEMALE -> s.blurFemale
            g.gender == Gender.MALE -> s.blurMale
            else -> s.blurUncertain
        }
    }
}
