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
}
