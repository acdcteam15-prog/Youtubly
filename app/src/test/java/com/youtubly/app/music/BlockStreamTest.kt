package com.youtubly.app.music

import com.youtubly.app.music.dsp.BlockStream
import com.youtubly.app.music.dsp.SpectralSeparator
import com.youtubly.app.music.dsp.SpectralSeparator.Companion.BLOCK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class BlockStreamTest {
    private class Result(val outL: FloatArray, val outR: FloatArray, val statuses: List<BlockStream.Status>)
    private val len = BLOCK * 4 + 12_345
    private val l = FloatArray(len) { sin(it * 0.013f) * 0.5f }
    private val r = FloatArray(len) { cos(it * 0.021f) * 0.4f }

    private fun run(modelDelayMs: Long, level: Float = 0f, floodInput: Boolean = false): Result {
        val statuses = mutableListOf<BlockStream.Status>()
        val sep = SpectralSeparator { _, m -> Thread.sleep(modelDelayMs); m.fill(0.5f) }
        val exec = Executors.newSingleThreadExecutor()
        val bs = BlockStream(object : BlockStream.Policy {
            override fun separator() = sep
            override fun wantsProcessing() = true
            override fun musicLevel() = level
            override fun previewOriginal() = false
            override fun onStatus(status: BlockStream.Status) { synchronized(statuses) { statuses += status } }
        }, exec)
        val outL = FloatArray(len); val outR = FloatArray(len)
        var inPos = 0; var outPos = 0
        val tl = FloatArray(4096); val tr = FloatArray(4096)
        while (!bs.isEnded) {
            while (inPos < len) {
                val n = minOf(4096, len - inPos)
                if (n > 0) {
                    System.arraycopy(l, inPos, tl, 0, n); System.arraycopy(r, inPos, tr, 0, n)
                    inPos += bs.write(tl, tr, n)
                }
                if (inPos == len) bs.endOfInput()
                if (!floodInput) break
            }
            val n = bs.read(tl, tr, 2048)
            if (n > 0) {
                System.arraycopy(tl, 0, outL, outPos, n); System.arraycopy(tr, 0, outR, outPos, n); outPos += n
                Thread.sleep(1)
            } else Thread.sleep(5)
        }
        exec.shutdownNow()
        assertEquals(len, outPos)
        return Result(outL, outR, statuses)
    }

    @Test fun fastModelProcessesEveryBlockInOrder() {
        val res = run(0)
        for (i in 0 until len step 101) {
            assertEquals(0.5f * l[i], res.outL[i], 1e-4f)
            assertEquals(0.5f * r[i], res.outR[i], 1e-4f)
        }
        assertTrue(BlockStream.Status.PROCESSING in res.statuses)
    }

    /**
     * Mirrors Media3's DefaultAudioSink: it only hands over new input when the processor has no
     * output pending, and output drains at playback speed. After the start-up burst, input
     * arrives exactly as fast as output leaves. With a model ~3× faster than real time every
     * block must be processed (this is the case that failed on the phone).
     */
    @Test fun realSinkPacingKeepsProcessing() {
        val statuses = mutableListOf<BlockStream.Status>()
        // One block "plays" in ~256 ms here (4 ms per 2048 frames); model 20 ms + FFTs ≈ 80 ms ≈ 3× real time.
        val sep = SpectralSeparator { _, m -> Thread.sleep(20); m.fill(0.5f) }
        val exec = Executors.newSingleThreadExecutor()
        val bs = BlockStream(object : BlockStream.Policy {
            override fun separator() = sep
            override fun wantsProcessing() = true
            override fun musicLevel() = 0f
            override fun previewOriginal() = false
            override fun onStatus(status: BlockStream.Status) { synchronized(statuses) { statuses += status } }
        }, exec, firstWaitNs = 128_000_000, lateWaitNs = 21_000_000, dataWaitNs = 86_000_000)   // 1.5 s / 250 ms / 1 s scaled like the audio
        val big = BLOCK * 12
        val src = FloatArray(big) { sin(it * 0.013f) * 0.5f }
        val outL = FloatArray(big); val outR = FloatArray(big)
        var inPos = 0; var outPos = 0
        val tl = FloatArray(4096); val tr = FloatArray(4096)
        while (!bs.isEnded) {
            val n = bs.read(tl, tr, 2048)
            if (n > 0) {                         // AudioTrack takes it at playback speed
                System.arraycopy(tl, 0, outL, outPos, n); System.arraycopy(tr, 0, outR, outPos, n); outPos += n
                Thread.sleep(4)
            } else if (inPos < big) {            // nothing pending → sink hands over the next decoder buffer
                val c = minOf(2048, big - inPos)
                System.arraycopy(src, inPos, tl, 0, c); System.arraycopy(src, inPos, tr, 0, c)
                inPos += bs.write(tl, tr, c)
                if (inPos == big) bs.endOfInput()
                if (inPos % (2048 * 8) == 0) Thread.sleep(1)   // decoder ≈ 30× real time (phones decode AAC/Opus far faster)
            } else Thread.sleep(1)
        }
        exec.shutdownNow()
        assertEquals(big, outPos)
        assertTrue("gave up: $statuses", BlockStream.Status.TOO_SLOW !in statuses)
        for (i in 0 until big step 101) assertEquals("sample $i", 0.5f * src[i], outL[i], 1e-4f)
    }

    /** The audio sink pushes input in big bursts; a large backlog must not trigger the fallback. */
    @Test fun floodedInputIsStillProcessed() {
        val res = run(0, floodInput = true)
        for (i in 0 until len step 101) assertEquals(0.5f * l[i], res.outL[i], 1e-4f)
    }

    @Test fun musicLevelBlendsOriginalBack() {
        val res = run(0, level = 0.5f)
        for (i in 0 until len step 101) assertEquals(0.75f * l[i], res.outL[i], 1e-4f)   // 0.5 + 0.5·(1 − 0.5)
    }

    @Test fun slowModelFallsBackToOriginalWithoutStalling() {
        val res = run(4_000)
        for (i in 0 until len step 101) assertTrue(abs(res.outL[i] - l[i]) < 1e-6f)
        assertTrue(BlockStream.Status.TOO_SLOW in res.statuses)
    }
}
