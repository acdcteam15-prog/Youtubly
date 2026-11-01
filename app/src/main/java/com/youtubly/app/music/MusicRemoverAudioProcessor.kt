package com.youtubly.app.music

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.youtubly.app.music.dsp.BlockStream
import com.youtubly.app.music.dsp.SpectralSeparator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ExoPlayer audio processor that keeps the voice and removes the music, a few seconds ahead of
 * playback (see [BlockStream]). Handles 16-bit and float PCM, mono or stereo, at any sample rate:
 * other rates (YouTube is 48 kHz) are converted to 44.1 kHz, the rate Spleeter was trained on,
 * and back, sample-aligned so A/V sync and the item's length are unchanged.
 *
 * Installed for the player's lifetime and always active for supported audio; every setting
 * (on/off, whitelist, model, music level, A/B preview) applies within a few seconds.
 */
@OptIn(UnstableApi::class)
class MusicRemoverAudioProcessor(private val engine: MusicRemover) : AudioProcessor {

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "music-remover").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }
    private var cachedSep: Pair<LoadedModel, SpectralSeparator>? = null

    private val stream = BlockStream(object : BlockStream.Policy {
        override fun separator(): SpectralSeparator? {
            val m = engine.liveModel() ?: return null
            val strength = engine.settings.value.strength
            cachedSep?.let { if (it.first === m) return it.second.also { sep -> sep.maskPower = strength } }
            return SpectralSeparator(m).also { it.maskPower = strength; cachedSep = m to it }   // worker thread only
        }
        override fun wantsProcessing() = engine.wantsProcessing()
        override fun onBlock(processed: Boolean) = engine.onBlock(processed)
        override fun onError(t: Throwable) = engine.onWorkerError(t)
        override fun musicLevel() = engine.settings.value.musicLevel
        override fun previewOriginal() = engine.previewOriginal.value
        override fun onStatus(status: BlockStream.Status) = engine.report(when (status) {
            BlockStream.Status.PROCESSING -> LiveStatus.PROCESSING
            BlockStream.Status.TOO_SLOW -> LiveStatus.TOO_SLOW
            BlockStream.Status.NO_MODEL -> LiveStatus.IDLE
            BlockStream.Status.BYPASSED ->
                if (engine.settings.value.enabled) LiveStatus.WHITELISTED else LiveStatus.OFF
        })
    }, worker)

    private var format = AudioFormat.NOT_SET
    private var seenItem = -1
    private var active = false
    private var channels = 2
    private var isFloat = false
    private var inputEnded = false
    private var outBuf: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private val inL = FloatArray(SLICE); private val inR = FloatArray(SLICE)
    private val outL = FloatArray(SLICE); private val outR = FloatArray(SLICE)
    private val midL = FloatArray(SLICE * 2); private val midR = FloatArray(SLICE * 2)
    // Only when the stream isn't 44.1 kHz.
    private var toModel: com.youtubly.app.music.dsp.Resampler? = null
    private var fromModel: com.youtubly.app.music.dsp.Resampler? = null
    private var sampleRate = MODEL_RATE
    private var framesIn = 0L
    private var framesOut = 0L

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) &&
            inputAudioFormat.channelCount in 1..2
        engine.onFormat("${inputAudioFormat.sampleRate} Hz · ${inputAudioFormat.channelCount} ch · " +
            when (inputAudioFormat.encoding) { C.ENCODING_PCM_16BIT -> "PCM16"; C.ENCODING_PCM_FLOAT -> "float"; else -> "encoding ${inputAudioFormat.encoding}" } +
            if (supported) "" else " (unsupported)")
        if (!supported) {
            if (engine.settings.value.enabled) engine.report(LiveStatus.UNSUPPORTED_AUDIO)
            format = AudioFormat.NOT_SET; return AudioFormat.NOT_SET
        }
        // Always active for supported audio, even while the remover is off: the sink doesn't
        // reconfigure between items of the same format, so an inactive processor would stay
        // inactive after the user turns the feature on. While off, blocks pass straight through.
        format = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = format != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Must consume everything: DefaultAudioSink loops forever if a processor leaves input behind.
        val bytesPerFrame = channels * (if (isFloat) 4 else 2)
        val b = inputBuffer.order(ByteOrder.nativeOrder())
        while (b.remaining() >= bytesPerFrame) {
            val frames = minOf(b.remaining() / bytesPerFrame, SLICE)
            for (i in 0 until frames) {
                val l = if (isFloat) b.float else b.short / 32768f
                val r = if (channels == 1) l else if (isFloat) b.float else b.short / 32768f
                inL[i] = l; inR[i] = r
            }
            framesIn += frames
            val rs = toModel
            if (rs == null) stream.write(inL, inR, frames)
            else { rs.write(inL, inR, frames); pumpToModel() }
        }
        b.position(b.limit())
    }

    private fun pumpToModel() {
        val rs = toModel ?: return
        while (true) {
            val n = rs.read(midL, midR, midL.size)
            if (n == 0) return
            stream.write(midL, midR, n)
        }
    }

    override fun queueEndOfStream() {
        inputEnded = true
        toModel?.let { it.finish(); pumpToModel() }
        stream.endOfInput()
    }

    override fun getOutput(): ByteBuffer {
        if (outBuf.hasRemaining()) return outBuf   // not fully drained yet
        pumpToModel()
        val n = nextOutput()
        if (n == 0) return AudioProcessor.EMPTY_BUFFER
        framesOut += n
        if (outBuf.capacity() < SLICE * 2 * 4) outBuf = ByteBuffer.allocateDirect(SLICE * 2 * 4).order(ByteOrder.nativeOrder())
        outBuf.clear()
        for (i in 0 until n) {
            if (isFloat) {
                outBuf.putFloat(outL[i]); if (channels == 2) outBuf.putFloat(outR[i])
            } else {
                outBuf.putShort(toShort(outL[i])); if (channels == 2) outBuf.putShort(toShort(outR[i]))
            }
        }
        outBuf.flip()
        return outBuf
    }

    /** Next output frames at the stream's own rate; never more than came in, exactly as many at the end. */
    private fun nextOutput(): Int {
        val back = fromModel ?: return stream.read(outL, outR, SLICE)
        val limit = (framesIn - framesOut).coerceAtMost(SLICE.toLong()).toInt()
        if (limit <= 0) return 0
        var n = back.read(outL, outR, limit)
        if (n == 0) {
            val m = stream.read(midL, midR, SLICE / 2)
            if (m > 0) { back.write(midL, midR, m); n = back.read(outL, outR, limit) }
            else if (inputEnded && stream.isEnded) {
                back.finish()
                n = back.read(outL, outR, limit)
                if (n == 0) { n = limit; outL.fill(0f, 0, n); outR.fill(0f, 0, n) }   // round-off tail
            }
        }
        return n
    }

    override fun isEnded(): Boolean = inputEnded && stream.isEnded && !outBuf.hasRemaining() &&
        (fromModel == null || framesOut >= framesIn)

    override fun flush() {
        android.util.Log.i("MusicRemover", "flush (format=${format.sampleRate} Hz, active=${isActive()})")
        // Called on seek and after configure(): take the new format and start a fresh block grid.
        outBuf = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        framesIn = 0; framesOut = 0
        if (format != AudioFormat.NOT_SET) {
            if (format.sampleRate != sampleRate || (toModel == null) != (format.sampleRate == MODEL_RATE)) {
                sampleRate = format.sampleRate
                toModel = if (sampleRate == MODEL_RATE) null else com.youtubly.app.music.dsp.Resampler(sampleRate, MODEL_RATE)
                fromModel = if (sampleRate == MODEL_RATE) null else com.youtubly.app.music.dsp.Resampler(MODEL_RATE, sampleRate)
            } else { toModel?.reset(); fromModel?.reset() }
        }
        if (format != AudioFormat.NOT_SET && (channels != format.channelCount || isFloat != (format.encoding == C.ENCODING_PCM_FLOAT) || !active)) {
            channels = format.channelCount
            isFloat = format.encoding == C.ENCODING_PCM_FLOAT
            active = true
            stream.reset()
        } else if (seenItem != engine.itemToken) stream.reset() else stream.flush()
        seenItem = engine.itemToken
    }

    override fun reset() {
        flush()
        format = AudioFormat.NOT_SET
        active = false
        stream.reset()
    }

    private fun toShort(v: Float): Short = (v * 32768f).toInt().coerceIn(-32768, 32767).toShort()

    private companion object {
        const val SLICE = 4096
        /** Spleeter's training rate. */
        const val MODEL_RATE = 44_100
    }
}
