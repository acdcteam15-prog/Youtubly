package com.youtubly.app.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.youtubly.app.core.Constants
import com.youtubly.app.domain.editor.SnapshotFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes finished clips to the public Download/Youtubly folder through MediaStore
 * (minSdk 29 = scoped storage, so no storage permission is needed and the files show up in
 * the Files app and gallery). The entry stays IS_PENDING until fully written, so a crash
 * mid-copy never leaves a truncated video visible.
 */
@Singleton
class MediaStoreSaver @Inject constructor(@ApplicationContext private val context: Context) {

    /** Saves an export ([mimeType] video/mp4 or audio/mp4) to Download/Youtubly. */
    suspend fun saveExport(file: File, displayName: String, mimeType: String): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${Constants.EDITOR_OUTPUT_RELATIVE_DIR}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw ExportFailed("Couldn't create the file in Downloads")
        try {
            val out = resolver.openOutputStream(uri) ?: throw ExportFailed("Couldn't write to Downloads")
            out.use { o -> file.inputStream().use { it.copyTo(o, bufferSize = 256 * 1024) } }
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** Saves a frame grab to Pictures/Youtubly (shows up in the gallery). */
    suspend fun saveImage(bitmap: Bitmap, displayName: String, format: SnapshotFormat): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/${Constants.EDITOR_OUTPUT_RELATIVE_DIR}")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw ExportFailed("Couldn't create the image in Pictures")
        try {
            val out = resolver.openOutputStream(uri) ?: throw ExportFailed("Couldn't write to Pictures")
            val ok = out.use { o ->
                when (format) {
                    SnapshotFormat.JPG -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, o)
                    SnapshotFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, o)
                }
            }
            if (!ok) throw ExportFailed("Couldn't encode the image")
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
            uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    suspend fun delete(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(uri, null, null) }
        Unit
    }

    /** Final name as MediaStore stored it (it appends " (1)" etc. on clashes). */
    suspend fun displayName(uri: Uri, fallback: String): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: fallback
    }
}
