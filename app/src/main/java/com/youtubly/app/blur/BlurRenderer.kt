package com.youtubly.app.blur

import android.graphics.*

/** Real region blur (the old BlurMaskFilter approach only softened a drawn rect, not the image). */
object BlurRenderer {

    fun render(src: Bitmap, regions: List<Rect>, mode: BlurMode, strength: Int, oval: Boolean = true, scaleWithSize: Boolean = false): Bitmap {
        if (regions.isEmpty()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val smooth = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val blocky = Paint()   // no filtering → hard pixel edges
        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40) }
        val bounds = Rect(0, 0, src.width, src.height)

        for (r0 in regions) {
            val r = Rect(r0); if (!r.intersect(bounds) || r.width() < 4 || r.height() < 4) continue
            val ovalPath = Path().apply { addOval(RectF(r), Path.Direction.CW) }
            canvas.save(); if (oval) canvas.clipPath(ovalPath)
            when (mode) {
                BlurMode.SOLID -> canvas.drawRect(r, solid)
                BlurMode.GAUSSIAN -> {
                    val crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                    val b = smoothBlur(crop, BlurMath.gaussianFactor(strength, minOf(r.width(), r.height()), scaleWithSize)); canvas.drawBitmap(b, null, r, smooth)
                    b.recycle(); crop.recycle()
                }
                BlurMode.PIXELATE -> {
                    val crop = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                    val blocks = (22 - strength * 18 / 50).coerceIn(4, 20)     // stronger → fewer blocks
                    val tiny = Bitmap.createScaledBitmap(crop, blocks, (blocks * r.height() / r.width()).coerceAtLeast(3), true)
                    canvas.drawBitmap(tiny, null, r, blocky)
                    tiny.recycle(); crop.recycle()
                }
            }
            canvas.restore()
        }
        return out
    }

    /** Downscale hard, then upscale in 2x steps with bilinear filtering → Gaussian-like result, very cheap. */
    private fun smoothBlur(src: Bitmap, factor: Int): Bitmap {
        var w = (src.width / factor).coerceAtLeast(2); var h = (src.height / factor).coerceAtLeast(2)
        var cur = Bitmap.createScaledBitmap(src, w, h, true)
        while (w * 2 < src.width && h * 2 < src.height) {
            w *= 2; h *= 2
            val next = Bitmap.createScaledBitmap(cur, w, h, true); cur.recycle(); cur = next
        }
        return cur
    }
}
