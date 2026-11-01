package com.youtubly.app.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.youtubly.app.domain.editor.EditorFormat
import com.youtubly.app.domain.editor.SnapshotFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot: the exact frame at the playhead, full resolution, saved to Pictures/Youtubly. */
@Singleton
class FrameGrabber @Inject constructor(private val saver: MediaStoreSaver) {

    suspend fun saveFrame(path: String, title: String, positionMs: Long, format: SnapshotFormat): Uri {
        val frame = withContext(Dispatchers.IO) { grab(path, positionMs) }
            ?: throw ExportFailed("Couldn't read a frame at this position")
        return try {
            saver.saveImage(frame, EditorFormat.snapshotFileName(title, positionMs, LocalDateTime.now(), format), format)
        } finally {
            frame.recycle()
        }
    }

    private fun grab(path: String, positionMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val us = positionMs.coerceAtLeast(0) * 1000
            // OPTION_CLOSEST = the exact frame (decodes from the previous keyframe); fall back to
            // the nearest keyframe if the exact decode fails on this device.
            retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
