package com.youtubly.app.editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.youtubly.app.domain.editor.ExportMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Share → Youtubly" with a video from another app: the content:// grant only lasts while
 * our activity is alive, and queued exports may run much later, so the file is copied into
 * app storage first. Only the most recent [KEEP] imports are kept.
 */
@Singleton
class SharedVideoImporter @Inject constructor(@ApplicationContext private val context: Context) {

    data class Imported(val path: String, val title: String)

    private val dir get() = File(context.filesDir, "editor_imports")

    suspend fun import(uriString: String): Imported = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) -1L else c.getLong(1)
                }
            }
        }
        dir.mkdirs()
        if (size > 0 && !ExportMath.hasRoomFor(size / 2, dir.usableSpace)) throw ExportFailed("Not enough free storage to import this video")
        val display = name?.takeIf { it.isNotBlank() } ?: "Shared video"
        val ext = display.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mp4"
        val target = File(dir, "${UUID.randomUUID()}.$ext")
        try {
            val input = resolver.openInputStream(uri) ?: throw ExportFailed("Couldn't open the shared video")
            input.use { i -> target.outputStream().use { o -> i.copyTo(o, bufferSize = 256 * 1024) } }
            if (target.length() == 0L) throw ExportFailed("The shared video is empty")
        } catch (e: SecurityException) {
            target.delete()
            throw ExportFailed("Youtubly wasn't allowed to read that video")
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        prune(keep = target)
        Imported(target.absolutePath, display.substringBeforeLast('.').ifBlank { "Shared video" })
    }

    private fun prune(keep: File) {
        val files = dir.listFiles()?.filter { it.isFile && it != keep } ?: return
        if (files.size < KEEP) return
        files.sortedBy { it.lastModified() }.take(files.size - KEEP + 1).forEach { it.delete() }
    }

    private companion object {
        const val KEEP = 5
    }
}
