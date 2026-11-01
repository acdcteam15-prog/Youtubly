package com.youtubly.app.data.system

import android.content.Context
import coil3.imageLoader
import com.youtubly.app.core.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One row on the Storage screen. [clearable] = safe to wipe from there (caches only). */
data class StorageItem(val id: String, val title: String, val subtitle: String, val bytes: Long, val clearable: Boolean)

/**
 * Measures and clears what the app keeps on the phone.
 *
 * Only caches are clearable here. Downloads and the gender model are shown but managed in their
 * own screens, so nothing the user chose to keep can be deleted by a "clear storage" tap.
 */
@Singleton
class StorageManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val cache get() = context.cacheDir
    private val thumbs get() = File(cache, "thumbnail_cache")
    private val blur get() = File(cache, "blur_cache")
    private val frames get() = File(cache, "editor_frames")
    private val exportTmp get() = File(cache, "editor_export")
    private val downloads get() = File(context.getExternalFilesDir(null), Constants.DOWNLOAD_DIR_NAME)
    private val model get() = File(context.filesDir, "blur")
    private val imports get() = File(context.filesDir, "editor_imports")

    suspend fun scan(): List<StorageItem> = withContext(Dispatchers.IO) {
        val known = listOf(thumbs, blur, frames, exportTmp).sumOf { sizeOf(it) }
        listOf(
            StorageItem("downloads", "Downloads", "Manage in the Saved tab", sizeOf(downloads), clearable = false),
            StorageItem("thumbs", "Thumbnail cache", "Re-downloads as you browse", sizeOf(thumbs), clearable = true),
            StorageItem("blur", "Blurred thumbnails", "Recomputed as you browse", sizeOf(blur), clearable = true),
            StorageItem("editor", "Editor temp files", "Timeline previews and leftover exports", sizeOf(frames) + sizeOf(exportTmp), clearable = true),
            StorageItem("imports", "Shared videos", "Videos shared into the editor", sizeOf(imports), clearable = false),
            StorageItem("model", "Gender model", "Remove in Content blur settings", sizeOf(model), clearable = false),
            StorageItem("other", "Other cache", "Temporary files", (sizeOf(cache) - known).coerceAtLeast(0), clearable = true),
        )
    }

    suspend fun clear(id: String) = withContext(Dispatchers.IO) {
        when (id) {
            "thumbs" -> {
                runCatching { context.imageLoader.diskCache?.clear() }
                runCatching { context.imageLoader.memoryCache?.clear() }
                thumbs.deleteRecursively()
            }
            "blur" -> {
                blur.deleteRecursively()
                runCatching { context.imageLoader.memoryCache?.clear() }
            }
            "editor" -> {
                frames.deleteRecursively()
                // An export may be writing here right now: only remove files a day old or more.
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                exportTmp.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            }
            "other" -> cache.listFiles()
                ?.filter { it.name !in setOf(thumbs.name, blur.name, frames.name, exportTmp.name) }
                // A model download / import may be in progress; never pull the file out from under it.
                ?.filterNot { it.name.startsWith("gender_") }
                ?.forEach { it.deleteRecursively() }
        }
        Unit
    }

    private fun sizeOf(f: File): Long =
        if (!f.exists()) 0 else if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
