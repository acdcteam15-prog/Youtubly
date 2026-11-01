package com.youtubly.app.music

import com.youtubly.app.music.dsp.SpectralSeparator
import com.youtubly.app.music.dsp.SpectralSeparator.Companion.BLOCK
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SpectralSeparatorTest {
    private val len = BLOCK * 3 + 50_000
    private val l = FloatArray(len) { (sin(it * 0.031) * 0.5 + sin(it * 0.0071) * 0.3).toFloat() }
    private val r = FloatArray(len) { (cos(it * 0.017) * 0.4 + ((it * 7919) % 1000 - 500) / 2000.0).toFloat() }
    private val src = SpectralSeparator.Source { c, i -> if (i < 0 || i >= len) 0f else if (c == 0) l[i.toInt()] else r[i.toInt()] }

    /** A mask of g everywhere must give g × input: checks STFT/iSTFT framing, window gain and both channels. */
    @Test fun constantMaskScalesInputExactly() {
        for (g in listOf(1f, 0.5f)) {
            val sep = SpectralSeparator { _, m -> m.fill(g) }
            for (k in 0 until 3L) {
                val out = sep.processBlock(k, src)
                for (s in 0 until BLOCK step 97) {
                    val i = (k * BLOCK + s).toInt()
                    assertEquals(g * l[i], out[s * 2], 1e-4f)
                    assertEquals(g * r[i], out[s * 2 + 1], 1e-4f)
                }
            }
        }
    }

    /** Block 0's model window ends at frame MODEL_FRAMES − PRE_FRAMES − 1; its last sample must be inside the lookahead. */
    @Test fun lookaheadCoversTheModelWindow() {
        val windowEnd = (SpectralSeparator.MODEL_FRAMES - SpectralSeparator.PRE_FRAMES - 1) * SpectralSeparator.HOP + SpectralSeparator.N
        assertEquals(windowEnd, BLOCK + SpectralSeparator.LOOKAHEAD)
    }
}
