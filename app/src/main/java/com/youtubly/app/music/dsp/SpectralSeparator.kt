package com.youtubly.app.music.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Runs a Spleeter-style mask model over one block of stereo audio (pure Kotlin, unit-tested).
 *
 * Framing matches Spleeter: 4096-point STFT, hop 1024, periodic Hann window, the model sees the
 * magnitude of the lowest 1024 bins of [MODEL_FRAMES] frames ([1, T, 1024, 2], NHWC) and returns a
 * vocal mask of the same shape. Bins above 1024 (roughly 11 kHz and up) reuse the average mask
 * of the top band, so sibilance isn't cut off completely.
 *
 * Block k produces output samples [k·B, (k+1)·B) with B = [BLOCK_FRAMES]·hop. Its model window
 * starts [PRE_FRAMES] frames before the block, so every frame that touches the block has
 * context on both sides. The block therefore needs input up to (k+1)·B + [LOOKAHEAD] samples.
 */
class SpectralSeparator(private val model: MaskModel) {

    /**
     * Removal strength: the vocal mask is raised to this power (1 = as trained, 2 = strong,
     * 3 = max). Bins that are only partly voice get pushed down harder, so music leaking under
     * speech drops, at the cost of a thinner voice. Read at the start of each block.
     */
    @Volatile var maskPower: Int = 1

    fun interface MaskModel {
        /** [magnitude] and [mask] are both [1, MODEL_FRAMES, MODEL_BINS, 2] row-major. */
        fun run(magnitude: FloatArray, mask: FloatArray)
    }

    /** Reads one sample (channel 0/1) at an absolute index; out-of-range must return 0. */
    fun interface Source { fun read(channel: Int, index: Long): Float }

    private val fft = Fft(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }
    private val bins = N / 2 + 1
    // Complex spectra of the whole window: [frame][bin] per channel.
    private val specLr = Array(MODEL_FRAMES) { FloatArray(bins) }
    private val specLi = Array(MODEL_FRAMES) { FloatArray(bins) }
    private val specRr = Array(MODEL_FRAMES) { FloatArray(bins) }
    private val specRi = Array(MODEL_FRAMES) { FloatArray(bins) }
    private val magnitude = FloatArray(MODEL_FRAMES * MODEL_BINS * 2)
    private val mask = FloatArray(MODEL_FRAMES * MODEL_BINS * 2)
    private val re = FloatArray(N)
    private val im = FloatArray(N)

    /**
     * Separates block [k]. Returns the voice as interleaved stereo floats, [BLOCK] frames long.
     * Not thread-safe: use one instance per worker.
     */
    fun processBlock(k: Long, src: Source): FloatArray {
        val firstFrame = k * BLOCK_FRAMES - PRE_FRAMES
        // ---- analysis: both channels in one complex FFT (left = real, right = imaginary) ----
        for (t in 0 until MODEL_FRAMES) {
            val start = (firstFrame + t) * HOP
            for (i in 0 until N) {
                re[i] = src.read(0, start + i) * window[i]
                im[i] = src.read(1, start + i) * window[i]
            }
            fft.transform(re, im)
            for (f in 0 until bins) {
                val nf = (N - f) and (N - 1)
                // L = (Z[f] + conj Z[N-f]) / 2,  R = (Z[f] - conj Z[N-f]) / 2i
                specLr[t][f] = 0.5f * (re[f] + re[nf]); specLi[t][f] = 0.5f * (im[f] - im[nf])
                specRr[t][f] = 0.5f * (im[f] + im[nf]); specRi[t][f] = 0.5f * (re[nf] - re[f])
            }
            val row = t * MODEL_BINS * 2
            for (f in 0 until MODEL_BINS) {
                magnitude[row + f * 2] = hypot(specLr[t][f], specLi[t][f])
                magnitude[row + f * 2 + 1] = hypot(specRr[t][f], specRi[t][f])
            }
        }
        model.run(magnitude, mask)
        when (maskPower) {
            2 -> for (i in mask.indices) mask[i] = mask[i] * mask[i]
            3 -> for (i in mask.indices) { val m = mask[i]; mask[i] = m * m * m }
        }

        // ---- synthesis: masked frames that overlap this block, overlap-added ----
        val out = FloatArray(BLOCK * 2)
        val blockStart = k * BLOCK
        val firstContributing = k * BLOCK_FRAMES - (N / HOP - 1)
        val lastContributing = k * BLOCK_FRAMES + BLOCK_FRAMES - 1
        for (frame in firstContributing..lastContributing) {
            val t = (frame - firstFrame).toInt()
            val row = t * MODEL_BINS * 2
            var hiL = 0f; var hiR = 0f
            for (f in MODEL_BINS - EXT_BAND until MODEL_BINS) { hiL += mask[row + f * 2]; hiR += mask[row + f * 2 + 1] }
            hiL /= EXT_BAND; hiR /= EXT_BAND
            // Z = Yl + i·Yr over the full (Hermitian) spectrum, so one inverse FFT gives both channels.
            for (f in 0 until bins) {
                val ml = if (f < MODEL_BINS) mask[row + f * 2] else hiL
                val mr = if (f < MODEL_BINS) mask[row + f * 2 + 1] else hiR
                val lr = specLr[t][f] * ml; val li = specLi[t][f] * ml
                val rr = specRr[t][f] * mr; val ri = specRi[t][f] * mr
                re[f] = lr - ri; im[f] = li + rr
                if (f in 1 until N / 2) {
                    val nf = N - f   // conj(Yl) + i·conj(Yr)
                    re[nf] = lr + ri; im[nf] = rr - li
                }
            }
            fft.transform(re, im, inverse = true)
            val frameStart = frame * HOP
            for (i in 0 until N) {
                val s = frameStart + i - blockStart
                if (s < 0 || s >= BLOCK) continue
                val g = window[i] * SYNTH_GAIN
                out[(s * 2).toInt()] += re[i] * g
                out[(s * 2 + 1).toInt()] += im[i] * g
            }
        }
        return out
    }

    private fun hypot(a: Float, b: Float) = sqrt(a * a + b * b)

    companion object {
        const val N = 4096
        const val HOP = 1024
        const val MODEL_BINS = 1024
        const val MODEL_FRAMES = 192
        const val BLOCK_FRAMES = 128
        const val PRE_FRAMES = 32
        const val BLOCK = BLOCK_FRAMES * HOP                 // 131 072 samples ≈ 2.97 s at 44.1 kHz
        /** Input needed past the end of a block before it can be processed. */
        const val LOOKAHEAD = (MODEL_FRAMES - PRE_FRAMES - BLOCK_FRAMES - 1) * HOP + N
        private const val EXT_BAND = 64
        /** 1/n for the unscaled inverse FFT, and 1/1.5 = 1/Σ w² for Hann at 75 % overlap. */
        private const val SYNTH_GAIN = 1f / (N * 1.5f)
    }
}
