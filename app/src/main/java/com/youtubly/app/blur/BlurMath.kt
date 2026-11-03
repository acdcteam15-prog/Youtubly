package com.youtubly.app.blur

/** Pure helpers for the renderer (no Android types, so they're unit-tested). */
object BlurMath {
    /**
     * Downscale factor for the Gaussian-like blur.
     *
     * Fixed mode (old behaviour): depends only on [strength], so a face filling the frame got the
     * same factor as a tiny background face and could stay recognisable.
     *
     * Scaled mode: the blur is a fraction of the face's own size, so every face ends up about
     * equally unrecognisable. Small faces get less processing, large faces get stronger blur.
     */
    fun gaussianFactor(strength: Int, faceMinSidePx: Int, scaleWithSize: Boolean): Int =
        if (!scaleWithSize) (strength / 2).coerceIn(3, 25)
        else (faceMinSidePx * strength / 400).coerceIn(2, 60)

    /**
     * Estimates the box of the whole person from the box of their face, using ordinary body proportions
     * (a standing adult is about 7-8 heads tall, shoulders about 2.5 heads wide). Everything is in the same pixel
     * space as the inputs and clipped to the image; a person cut off by the frame simply gets the part that is visible.
     *
     * @param growPct extra margin around the result, in percent of its size (movement, hair, loose clothes)
     * @return [left, top, right, bottom]
     */
    fun bodyBox(left: Int, top: Int, right: Int, bottom: Int, imgW: Int, imgH: Int, coverage: BodyCoverage, growPct: Int = 0): IntArray {
        val w = (right - left).toFloat()
        val h = (bottom - top).toFloat()
        val cx = (left + right) / 2f
        // (half width in face widths, extent below the chin in face heights)
        val (halfW, below) = when (coverage) {
            BodyCoverage.FACE -> 0.5f to 0f
            BodyCoverage.UPPER_BODY -> 1.7f to 3.2f
            BodyCoverage.FULL_BODY -> 1.9f to 8.0f
        }
        var l = cx - halfW * w
        var r = cx + halfW * w
        var t = top - 0.5f * h                     // hair and forehead
        var b = bottom + below * h
        val gx = (r - l) * growPct / 100f
        val gy = (b - t) * growPct / 100f
        l -= gx; r += gx; t -= gy; b += gy
        return intArrayOf(
            l.toInt().coerceAtLeast(0), t.toInt().coerceAtLeast(0),
            r.toInt().coerceAtMost(imgW), b.toInt().coerceAtMost(imgH)
        )
    }
}
