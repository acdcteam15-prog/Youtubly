package com.youtubly.app.music.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Streaming stereo sample-rate converter (windowed sinc, ±[HALF] input taps). Pure Kotlin.
 *
 * Output sample j sits at input time j·inRate/outRate with no added delay, so a
 * 48 k → 44.1 k → 48 k round trip stays sample-aligned with the original.
 * It needs [HALF] samples of lookahead; [finish] lets the tail through (zero-padded).
 */
class Resampler(inRate: Int, outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private val cutoff = minOf(1.0, outRate.toDouble() / inRate) * 0.97
    private val table = FloatArray(HALF * PHASES + 2).also { t ->
        for (i in t.indices) {
            val x = i.toDouble() / PHASES
            val s = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
            val w = if (x >= HALF) 0.0 else 0.42 + 0.5 * cos(PI * x / HALF) + 0.08 * cos(2 * PI * x / HALF)  // Blackman
            t[i] = (cutoff * s * w).toFloat()
        }
    }
    private var bufL = FloatArray(1 shl 15)
    private var bufR = FloatArray(1 shl 15)
    private var base = 0L        // absolute input index of buf[0]
    private var count = 0        // samples held
    private var produced = 0L    // outputs so far
    private var finished = false

    fun write(l: FloatArray, r: FloatArray, n: Int) {
        if (count + n > bufL.size) {
            // Drop what no future output can reach, then grow if still needed.
            val keepFrom = (floor(produced * step).toLong() - HALF - 1).coerceAtLeast(base)
            val drop = (keepFrom - base).toInt()
            System.arraycopy(bufL, drop, bufL, 0, count - drop); System.arraycopy(bufR, drop, bufR, 0, count - drop)
            count -= drop; base = keepFrom
            if (count + n > bufL.size) {
                val size = Integer.highestOneBit(count + n) * 2
                bufL = bufL.copyOf(size); bufR = bufR.copyOf(size)
            }
        }
        System.arraycopy(l, 0, bufL, count, n); System.arraycopy(r, 0, bufR, count, n)
        count += n
    }

    /** No more input: the remaining outputs (up to the input's length) can be produced. */
    fun finish() { finished = true }

    /** Produces up to [max] frames; returns how many. */
    fun read(outL: FloatArray, outR: FloatArray, max: Int): Int {
        val end = base + count
        var n = 0
        while (n < max) {
            val t = produced * step
            if (finished) { if (t >= end) break } else if (t + HALF + 1 >= end) break
            val center = floor(t).toLong()
            val frac = t - center
            var sl = 0f; var sr = 0f
            for (k in -HALF + 1..HALF) {
                val idx = center + k
                if (idx < base || idx >= end) continue
                val x = kotlin.math.abs(k - frac) * PHASES
                val xi = x.toInt()
                if (xi >= HALF * PHASES) continue
                val a = x - xi
                val h = (table[xi] * (1 - a) + table[xi + 1] * a).toFloat()
                val bi = (idx - base).toInt()
                sl += bufL[bi] * h; sr += bufR[bi] * h
            }
            outL[n] = sl; outR[n] = sr
            n++; produced++
        }
        return n
    }

    fun reset() { base = 0; count = 0; produced = 0; finished = false }

    companion object {
        const val HALF = 16
        private const val PHASES = 256
    }
}
