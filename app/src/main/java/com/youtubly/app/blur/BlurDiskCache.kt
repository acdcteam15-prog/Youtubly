package com.youtubly.app.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/**
 * Keeps already-blurred thumbnails on disk, so scrolling back through a feed after the memory
 * cache is cleared (or after an app restart) doesn't run face detection again.
 *
 * Key = the image's source (URL) + the transformation's cache key (settings + model id), so
 * changing a blur setting or the model can never return a stale picture. Only *successful*
 * results are stored; the fail-closed "blur everything" fallback is never cached.
 */
class BlurDiskCache(context: Context) {
    val dir = File(context.cacheDir, "blur_cache")
    private var writesSinceTrim = 0

    fun get(source: String, key: String): Bitmap? {
        val f = file(source, key)
        if (!f.exists()) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = false }
        return runCatching { BitmapFactory.decodeFile(f.path, opts) }.getOrNull()
            ?.also { f.setLastModified(System.currentTimeMillis()) }     // LRU by mtime
            ?: run { f.delete(); null }
    }

    fun put(source: String, key: String, bmp: Bitmap) {
        runCatching {
            dir.mkdirs()
            val f = file(source, key)
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, it) }
            tmp.renameTo(f)
            if (++writesSinceTrim >= 40) { writesSinceTrim = 0; trim() }
        }
    }

    fun clear() { dir.deleteRecursively() }

    /** Drops least-recently-used files until the cache is under [MAX_BYTES]. */
    @Synchronized fun trim() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES) break
            total -= f.length(); f.delete()
        }
    }

    private fun file(source: String, key: String) = File(dir, sha1("$source|$key") + ".webp")

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object { const val MAX_BYTES = 100L * 1024 * 1024 }
}
