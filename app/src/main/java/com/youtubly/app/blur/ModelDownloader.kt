package com.youtubly.app.blur

import android.content.Context
import com.youtubly.app.core.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads the gender-classification .tflite from Hugging Face into the app cache.
 *
 * The source is the "Model source" the user typed in the app (`username/repo`, or a link); if that's
 * empty it falls back to [Constants.GENDER_MODEL_REPO]. The file is only *fetched* here. Shape
 * validation and installing it as the active model is done by [GenderClassifier.importFromFile],
 * so a bad download can never replace a working model.
 */
class ModelDownloader(
    private val context: Context,
    private val client: OkHttpClient,
    private val store: BlurPreferencesStore,
) {
    /**
     * @param onProgress 0..100, or -1 when the server doesn't report a length.
     * @return the downloaded temp file; the caller owns (and must delete) it.
     */
    suspend fun download(onProgress: (Int) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "gender_download.tflite.part")
        runCatching {
            val typed = store.modelSource.first().trim()
            val usingDefault = typed.isEmpty()
            val source = typed.ifEmpty { Constants.GENDER_MODEL_REPO.takeUnless { it.startsWith("REPLACE_WITH") }.orEmpty() }
            if (source.isEmpty()) throw IOException(
                "No model source yet. Enter the Hugging Face repo that hosts your .tflite (username/repo) under Model source."
            )
            val url = ModelSource.resolveUrl(source, Constants.GENDER_MODEL_FILE, Constants.GENDER_MODEL_REVISION)
                ?: throw IOException("Model source must be username/repo, or a huggingface.co link to a .tflite file.")
            tmp.delete()

            val request = Request.Builder().url(url).header("Accept", "application/octet-stream").build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 ->
                        throw IOException("Hugging Face refused access (HTTP ${resp.code}). The repo must be public.")
                    resp.code == 404 ->
                        throw IOException("Not found on Hugging Face (HTTP 404). Check the repo name, and that it contains a .tflite file with that name.")
                    !resp.isSuccessful -> throw IOException("Download failed (HTTP ${resp.code}).")
                }
                val body = resp.body ?: throw IOException("Empty response from Hugging Face.")
                if (body.contentType()?.subtype == "html") {
                    throw IOException("Got a web page instead of a model file. Check the link points to the file itself.")
                }

                val total = body.contentLength()
                var read = 0L
                var lastPct = -2
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            val pct = if (total > 0) (read * 100 / total).toInt() else -1
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }

            if (tmp.length() < 1_000) throw IOException("Downloaded file is too small to be a model.")
            val head = ByteArray(8).also { h -> tmp.inputStream().use { it.read(h) } }
            if (!ModelSource.looksLikeTflite(head)) throw IOException(
                "That file isn't a TensorFlow Lite model. PyTorch (.pt) and ONNX files must be converted to .tflite first (see docs/CONTENT_BLUR.md)."
            )
            // The pinned hash belongs to the built-in source only, never to a repo the user typed.
            val expected = Constants.GENDER_MODEL_SHA256.trim().lowercase()
            if (usingDefault && expected.isNotEmpty() && sha256(tmp) != expected) {
                throw IOException("Downloaded model failed its integrity check. It was not installed.")
            }
            tmp
        }.onFailure {
            tmp.delete()
            // runCatching also swallows cancellation; rethrow so leaving the screen just stops the download.
            if (it is kotlinx.coroutines.CancellationException) throw it
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(16 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
