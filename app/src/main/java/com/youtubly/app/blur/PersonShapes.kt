package com.youtubly.app.blur

/** Per-pixel "is a person" confidence (0..1), row by row. */
class PersonMask(val values: FloatArray, val width: Int, val height: Int)

/** Pure helpers (no Android types, unit-tested) for covering people whose face cannot be seen. */
object PersonShapes {
    /**
     * Separate foreground blobs in [mask] (4-connected pixels at or above [threshold]) that cover at least
     * [minAreaFrac] of the picture, as [left, top, right, bottom] boxes. Tiny specks are ignored.
     */
    fun blobs(mask: PersonMask, threshold: Float = 0.5f, minAreaFrac: Float = 0.015f): List<IntArray> {
        val w = mask.width
        val h = mask.height
        if (w <= 0 || h <= 0 || mask.values.size < w * h) return emptyList()
        val v = mask.values
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val minArea = (w * h * minAreaFrac).toInt().coerceAtLeast(4)
        val out = ArrayList<IntArray>()
        for (start in 0 until w * h) {
            if (seen[start] || v[start] < threshold) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            var area = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) { val q = p - 1; if (!seen[q] && v[q] >= threshold) { seen[q] = true; queue[tail++] = q } }
                if (x < w - 1) { val q = p + 1; if (!seen[q] && v[q] >= threshold) { seen[q] = true; queue[tail++] = q } }
                if (y > 0) { val q = p - w; if (!seen[q] && v[q] >= threshold) { seen[q] = true; queue[tail++] = q } }
                if (y < h - 1) { val q = p + w; if (!seen[q] && v[q] >= threshold) { seen[q] = true; queue[tail++] = q } }
            }
            if (area >= minArea) out += intArrayOf(minX, minY, maxX + 1, maxY + 1)
        }
        return out
    }

    /**
     * Blobs that contain no detected face: people seen from behind (or with the face turned away or too small).
     * A face belongs to a blob when its centre lies inside the blob's box.
     */
    fun withoutFace(blobs: List<IntArray>, faces: List<IntArray>): List<IntArray> = blobs.filterNot { b ->
        faces.any { f ->
            val cx = (f[0] + f[2]) / 2
            val cy = (f[1] + f[3]) / 2
            cx >= b[0] && cx < b[2] && cy >= b[1] && cy < b[3]
        }
    }

    /**
     * The box to hide for one blob. Upper body stops at about the waist (a person's upper body is roughly 1.4 times
     * as tall as it is wide); full body takes the whole blob. [growPct] adds a margin; the result is clipped to the image.
     */
    fun regionFor(blob: IntArray, coverage: BodyCoverage, imgW: Int, imgH: Int, growPct: Int = 0): IntArray {
        val w = blob[2] - blob[0]
        val bottom = if (coverage == BodyCoverage.UPPER_BODY) minOf(blob[3], blob[1] + (w * 1.4f).toInt()) else blob[3]
        val l = blob[0].toFloat()
        val t = blob[1].toFloat()
        val r = blob[2].toFloat()
        val b = bottom.toFloat()
        val gx = (r - l) * growPct / 100f
        val gy = (b - t) * growPct / 100f
        return intArrayOf(
            (l - gx).toInt().coerceAtLeast(0), (t - gy).toInt().coerceAtLeast(0),
            (r + gx).toInt().coerceAtMost(imgW), (b + gy).toInt().coerceAtMost(imgH)
        )
    }
}
