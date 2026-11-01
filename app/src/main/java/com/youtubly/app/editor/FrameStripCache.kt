package com.youtubly.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import com.youtubly.app.domain.editor.ExportMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thumbnails for the editor timeline. Frames are cached in memory (for going back and forth
 * between tabs) and as small JPEGs on disk (for reopening the same video later), keyed by
 * path + size + modification time so a replaced file never shows stale frames.
 */
@Singleton
class FrameStripCache @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir = File(context.cacheDir, "editor_frames")
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Emits the strip progressively: [count] slots, each null until its frame is ready. */
    fun frames(path: String, durationMs: Long, count: Int): Flow<List<Bitmap?>> = flow {
        val file = File(path)
        val times = ExportMath.frameTimesMs(durationMs, count)
        val result = MutableList<Bitmap?>(times.size) { null }
        if (times.isEmpty() || !file.isFile) { emit(result); return@flow }
        val key = keyFor(file, count)
        val keyDir = File(dir, key)

        times.indices.forEach { i -> result[i] = memory.get("$key/$i") ?: readDisk(File(keyDir, "$i.jpg"))?.also { memory.put("$key/$i", it) } }
        emit(result.toList())
        if (result.all { it != null }) return@flow

        trimDiskCache(keep = key)
        keyDir.mkdirs()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            for (i in times.indices) {
                if (result[i] != null) continue
                currentCoroutineContext().ensureActive()
                val frame = runCatching {
                    retriever.getScaledFrameAtTime(times[i] * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_BOX, FRAME_BOX)
                }.getOrNull() ?: continue
                result[i] = frame
                memory.put("$key/$i", frame)
                runCatching { File(keyDir, "$i.jpg").outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
                emit(result.toList())
            }
        } catch (e: CancellationException) {
            throw e // collector gone (screen closed / new video): must propagate, never swallow
        } catch (e: RuntimeException) {
            // Unreadable / broken file (IllegalArgumentException or a plain RuntimeException from
            // MediaMetadataRetriever): keep what we have, placeholders for the rest.
        } finally {
            runCatching { retriever.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun readDisk(file: File): Bitmap? =
        if (file.isFile) runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() else null

    private fun keyFor(file: File, count: Int): String {
        val raw = "${file.absolutePath}|${file.length()}|${file.lastModified()}|$count"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Keeps the disk cache to the most recent videos. */
    private fun trimDiskCache(keep: String) {
        val dirs = dir.listFiles()?.filter { it.isDirectory && it.name != keep } ?: return
        if (dirs.size < MAX_VIDEOS) return
        dirs.sortedBy { it.lastModified() }.take(dirs.size - MAX_VIDEOS + 1).forEach { it.deleteRecursively() }
    }

    private companion object {
        const val FRAME_BOX = 192
        const val MAX_VIDEOS = 20
    }
}
