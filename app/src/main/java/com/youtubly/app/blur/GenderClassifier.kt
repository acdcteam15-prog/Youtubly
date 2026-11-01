package com.youtubly.app.blur

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import com.google.ai.edge.litert.DataType
import com.google.ai.edge.litert.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

data class GenderResult(val gender: Gender, val confidence: Float)
data class ModelInfo(val inputW: Int, val inputH: Int, val inputType: String, val outputs: Int, val id: Long, val sizeKb: Long, val bundled: Boolean = false)

/**
 * Optional user-imported TFLite model. Supports:
 *  - input [1,H,W,3] float32 or uint8
 *  - output [1,1] (sigmoid) or [1,2] (probs or logits)
 * Without a model every face is treated as UNKNOWN (safe: blurred when "blur uncertain" is on).
 */
class GenderClassifier(private val context: Context) {
    private val modelFile get() = File(context.filesDir, "blur/gender.tflite")
    private var interpreter: Interpreter? = null
    private val _info = MutableStateFlow<ModelInfo?>(null)
    val info: StateFlow<ModelInfo?> = _info

    // ---- models packed inside the APK (see BundledModels.kt, assets/models/) ----
    private val originFile get() = File(context.filesDir, "blur/origin.txt")

    /** Every model this build carries. Empty only if the assets were stripped. */
    val bundledModels: List<BundledModel> by lazy {
        val present = runCatching { context.assets.list(BundledModels.ASSET_DIR)?.toSet() }.getOrNull().orEmpty()
        BundledModels.all.filter { it.fileName in present }
    }
    /** True when this build has at least one gender model packed inside it. */
    val hasBundled: Boolean get() = bundledModels.isNotEmpty()

    private val _activeBundledId = MutableStateFlow<String?>(null)
    /** Id of the packed model currently installed, or null when a custom file (or nothing) is. */
    val activeBundledId: StateFlow<String?> = _activeBundledId

    // Installed *before* the first load, so the first-launch "import model" screen never flashes up
    // when a packed model exists.
    init { runCatching { installBundledIfNeeded() }; runCatching { load() } }

    private fun readOrigin(): String = runCatching { originFile.readText().trim() }.getOrDefault("")
    private fun writeOrigin(v: String) {
        runCatching { if (v.isEmpty()) originFile.delete() else { originFile.parentFile?.mkdirs(); originFile.writeText(v) } }
    }

    @Synchronized private fun installBundledIfNeeded() {
        if (!hasBundled) return
        when (ModelOrigin.onStart(readOrigin(), modelFile.exists())) {
            ModelOrigin.Action.INSTALL_DEFAULT ->
                (bundledModels.firstOrNull { it.recommended } ?: bundledModels.first()).let { copyBundled(it) }
            ModelOrigin.Action.MARK_CUSTOM -> writeOrigin("custom")
            ModelOrigin.Action.KEEP -> Unit
        }
    }

    /** Copies a packed model into place and loads it. A packed file that won't load is discarded. */
    @Synchronized private fun copyBundled(model: BundledModel) {
        modelFile.parentFile!!.mkdirs()
        val tmp = File(modelFile.path + ".tmp")
        context.assets.open(model.assetPath).use { i -> tmp.outputStream().use { i.copyTo(it) } }
        tmp.copyTo(modelFile, overwrite = true); tmp.delete()
        writeOrigin(ModelOrigin.forModel(model.id))
        try { load() } catch (t: Throwable) { modelFile.delete(); writeOrigin(""); _activeBundledId.value = null; throw t }
    }

    /** Switches to one of the packed models. Returns it so the caller can apply its profile. */
    suspend fun useBundled(id: String): Result<BundledModel> = withContext(Dispatchers.IO) {
        runCatching {
            val model = bundledModels.firstOrNull { it.id == id } ?: error("That model isn't part of this build")
            copyBundled(model)
            _info.value ?: error("${model.title} failed to load")
            model
        }
    }

    /** "Use the built-in model": puts the recommended packed model back after the user removed or replaced it. */
    suspend fun restoreBundled(): Result<BundledModel> {
        val m = bundledModels.firstOrNull { it.recommended } ?: bundledModels.firstOrNull()
            ?: return Result.failure(IllegalStateException("This build has no built-in model"))
        return useBundled(m.id)
    }

    @Synchronized private fun load() {
        interpreter?.close(); interpreter = null; _info.value = null
        if (!modelFile.exists()) return
        val interp = Interpreter(modelFile, Interpreter.Options().setNumThreads(2))
        val inShape = interp.getInputTensor(0).shape()
        val outShape = interp.getOutputTensor(0).shape()
        require(inShape.size == 4 && inShape[3] == 3) { "Expected input [1,H,W,3], got ${inShape.contentToString()}" }
        require(outShape.last() in 1..2) { "Expected 1 or 2 outputs, got ${outShape.contentToString()}" }
        interpreter = interp
        val origin = readOrigin()
        _activeBundledId.value = ModelOrigin.bundledId(origin)?.takeIf { id -> BundledModels.byId(id) != null }
        _info.value = ModelInfo(inShape[2], inShape[1], interp.getInputTensor(0).dataType().name, outShape.last(),
            id = modelFile.lastModified(), sizeKb = modelFile.length() / 1024,
            bundled = ModelOrigin.isBundled(origin))
    }

    /** Legacy: import a model the user picked from their files. Kept for API compatibility. */
    suspend fun import(uri: Uri): Result<ModelInfo> = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "gender_import.tflite")
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        }.fold(
            onSuccess = { importFromFile(tmp) },
            onFailure = { tmp.delete(); Result.failure(it) },
        )
    }

    /**
     * Validates [src] fully (shapes) and only then installs it as the active model, so a bad
     * file can never replace a working one. [src] is deleted afterwards (success or failure).
     */
    suspend fun importFromFile(src: File): Result<ModelInfo> = withContext(Dispatchers.IO) {
        runCatching {
            require(src.length() in 1_000..200_000_000) { "File size doesn't look like a model" }
            Interpreter(src).use { i ->
                val inS = i.getInputTensor(0).shape(); val outS = i.getOutputTensor(0).shape()
                require(inS.size == 4 && inS[3] == 3) { "Not an image model (input ${inS.contentToString()})" }
                require(outS.last() in 1..2) { "Not a 2-class model (output ${outS.contentToString()})" }
            }
            modelFile.parentFile!!.mkdirs()
            src.copyTo(modelFile, overwrite = true)
            writeOrigin("custom")
            load()
            _info.value ?: error("Model failed to load")
        }.onFailure { runCatching { load() } }
            .also { src.delete() }
    }

    /** SHA-256 of the installed model (lowercase hex), or null when none. For pinning in Constants. */
    suspend fun sha256(): String? = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) return@withContext null
        val md = java.security.MessageDigest.getInstance("SHA-256")
        modelFile.inputStream().use { s ->
            val buf = ByteArray(16 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun remove() {
        interpreter?.close(); interpreter = null; modelFile.delete(); _info.value = null; _activeBundledId.value = null
        writeOrigin(if (hasBundled) "removed" else "")   // a removal is respected: the packed model isn't silently re-installed
    }

    /** Which output slot is "female": a packed model's own answer, or the user's manual choice for a custom file. */
    private fun effectiveFemaleIndex(s: BlurSettings): Int {
        val bundled = BundledModels.byId(_activeBundledId.value) ?: return s.femaleOutputIndex
        return if (s.flipGender) 1 - bundled.femaleIndex else bundled.femaleIndex
    }
    private fun effectiveNormalization(s: BlurSettings): Normalization =
        BundledModels.byId(_activeBundledId.value)?.normalization ?: s.normalization

    private var pixelScratch = IntArray(0)
    private var inputScratch: ByteBuffer? = null

    @Synchronized
    fun classify(face: Bitmap, s: BlurSettings): GenderResult? {
        val interp = interpreter ?: return null
        val femaleIdx = effectiveFemaleIndex(s)
        val norm = effectiveNormalization(s)
        val inT = interp.getInputTensor(0)
        val (h, w) = inT.shape().let { it[1] to it[2] }
        val scaled = Bitmap.createScaledBitmap(face, w, h, true)
        // Reused between calls (this method is @Synchronized): the input size is fixed per model,
        // and per-face allocations here were a big part of the GC churn during playback/export.
        val px = pixelScratch.takeIf { it.size == w * h } ?: IntArray(w * h).also { pixelScratch = it }
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== face) scaled.recycle()

        val isFloat = inT.dataType() == DataType.FLOAT32
        val bytes = w * h * 3 * if (isFloat) 4 else 1
        val buf = inputScratch?.takeIf { it.capacity() == bytes }
            ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { inputScratch = it }
        buf.clear()
        fun put(c: Int) {
            if (!isFloat) buf.put(c.toByte()) else buf.putFloat(
                when (norm) {
                    Normalization.ZERO_ONE -> c / 255f
                    Normalization.MINUS_ONE_ONE -> c / 127.5f - 1f
                    Normalization.RAW -> c.toFloat()
                }
            )
        }
        for (p in px) { put(p shr 16 and 0xFF); put(p shr 8 and 0xFF); put(p and 0xFF) } // no per-pixel arrays
        buf.rewind()

        val outT = interp.getOutputTensor(0)
        val n = outT.shape().last()
        val probs: FloatArray = if (outT.dataType() == DataType.FLOAT32) {
            val out = Array(1) { FloatArray(n) }; interp.run(buf, out); out[0]
        } else {
            val out = Array(1) { ByteArray(n) }; interp.run(buf, out)
            FloatArray(n) { (out[0][it].toInt() and 0xFF) / 255f }
        }

        val pFemale = if (n == 1) {
            val v = probs[0].let { if (it in 0f..1f) it else sigmoid(it) }   // v = P(output index 1 class)
            if (femaleIdx == 0) 1f - v else v
        } else {
            val sm = if (probs.all { it in 0f..1f } && kotlin.math.abs(probs.sum() - 1f) < 0.05f) probs else softmax(probs)
            sm[femaleIdx]
        }
        return if (pFemale >= 0.5f) GenderResult(Gender.FEMALE, pFemale) else GenderResult(Gender.MALE, 1f - pFemale)
    }

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))
    private fun softmax(a: FloatArray): FloatArray {
        val m = a.max(); val e = a.map { exp(it - m) }; val s = e.sum()
        return FloatArray(a.size) { e[it] / s }
    }
}
