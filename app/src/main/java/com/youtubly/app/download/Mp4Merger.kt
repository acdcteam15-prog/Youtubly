package com.youtubly.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins a video-only MP4 + audio-only M4A into one MP4 (or, for a .webm target, VP9 + Opus into one WebM) by copying the encoded samples
 * (no re-encoding: fast, lossless, little battery). Used for HD downloads, which YouTube only
 * serves as separate streams.
 */
object Mp4Merger {

    fun merge(video: File, audio: File, out: File) {
        val tmp = File(out.parentFile, "${out.name}.muxing")
        tmp.delete()
        val vEx = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val aEx = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        var muxer: MediaMuxer? = null
        try {
            val vTrack = firstTrack(vEx, "video/") ?: error("No video track in download")
            val aTrack = firstTrack(aEx, "audio/") ?: error("No audio track in download")
            vEx.selectTrack(vTrack); aEx.selectTrack(aTrack)
            val vFormat = vEx.getTrackFormat(vTrack)
            val aFormat = aEx.getTrackFormat(aTrack)

            val container = if (out.extension.equals("webm", true)) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            muxer = MediaMuxer(tmp.absolutePath, container)
            if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(vFormat.getInteger(MediaFormat.KEY_ROTATION))
            val outV = muxer.addTrack(vFormat)
            val outA = muxer.addTrack(aFormat)
            muxer.start()

            val size = maxOf(maxInput(vFormat), maxInput(aFormat), 1 shl 20)
            val buf = ByteBuffer.allocateDirect(size)
            copy(vEx, muxer, outV, buf)
            copy(aEx, muxer, outA, buf)
            muxer.stop()
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        } finally {
            runCatching { muxer?.release() }
            vEx.release(); aEx.release()
        }
        out.delete()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
    }

    private fun firstTrack(ex: MediaExtractor, prefix: String): Int? =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }

    private fun maxInput(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0

    private fun copy(ex: MediaExtractor, muxer: MediaMuxer, track: Int, buf: ByteBuffer) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            info.offset = 0
            info.size = n
            info.presentationTimeUs = ex.sampleTime
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(track, buf, info)
            ex.advance()
        }
    }
}
