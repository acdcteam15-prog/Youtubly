package com.youtubly.app.music

import android.content.Context
import android.util.Log
import com.youtubly.app.blur.channelKeyOf
import com.youtubly.app.music.dsp.SpectralSeparator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** What the live processor is doing right now, for the settings page and player. */
enum class LiveStatus { OFF, IDLE, PROCESSING, WHITELISTED, TOO_SLOW, UNSUPPORTED_AUDIO }

/**
 * Music remover engine: owns the settings, the loaded model and the live status.
 * The per-player audio work happens in [MusicRemoverAudioProcessor], which asks this engine
 * what to do at every block, so setting changes apply within a few seconds.
 */
@Singleton
class MusicRemover @Inject constructor(
    @ApplicationContext private val context: Context,
    val store: MusicPreferencesStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings: StateFlow<MusicSettings> = store.settings.stateIn(scope, SharingStarted.Eagerly, MusicSettings())

    /** Channel of the item now playing (null for local files → never whitelisted). */
    @Volatile var channelKey: String? = null
        private set

    /** Preview lab: play the untouched sound while still processing, for instant A/B. */
    val previewOriginal = MutableStateFlow(false)

    private val _status = MutableStateFlow(LiveStatus.OFF)
    val status: StateFlow<LiveStatus> = _status

    /** Called by PlayerController right before each prepare(). */
    /** Bumped for every new item so the processor forgets "too slow" from the previous video. */
    @Volatile var itemToken = 0
        private set

    fun onNewItem(uploaderUrl: String?) {
        itemToken++
        channelKey = channelKeyOf(uploaderUrl)
        _status.value = if (settings.value.enabled) LiveStatus.IDLE else LiveStatus.OFF
    }

    /** Should the block starting now be processed? Read by the audio thread. */
    fun wantsProcessing(): Boolean {
        val s = settings.value
        if (!s.enabled) return false
        val ch = channelKey
        return ch == null || ch !in s.whitelist
    }

    internal fun report(status: LiveStatus) { _status.value = status }

    /** Live counters from the audio processor, shown on the settings page to see what's happening. */
    data class Debug(
        val format: String = "no audio yet",
        val processed: Int = 0,
        val original: Int = 0,
        val lastError: String? = null,
    )
    private val _debug = MutableStateFlow(Debug())
    val debug: StateFlow<Debug> = _debug
    internal fun onFormat(desc: String) { Log.i(TAG, "configure: $desc"); _debug.value = _debug.value.copy(format = desc) }
    internal fun onBlock(processed: Boolean) = _debug.value.let {
        Log.i(TAG, "block → ${if (processed) "PROCESSED" else "original"} (enabled=${settings.value.enabled}, want=${wantsProcessing()}, level=${settings.value.musicLevel}, previewOriginal=${previewOriginal.value})")
        _debug.value = if (processed) it.copy(processed = it.processed + 1) else it.copy(original = it.original + 1)
    }
    internal fun onWorkerError(t: Throwable) {
        Log.e(TAG, "worker failed", t)
        _debug.value = _debug.value.copy(lastError = "${t.javaClass.simpleName}: ${t.message}")
        com.youtubly.app.core.ErrorLog.record("Music remover", t)
    }

    init {
        // Load the model as soon as the feature is on, so the first block isn't slowed by it.
        scope.launch { settings.collect { if (it.enabled) liveModel() } }
    }

    // ---------------- model ----------------
    private val lock = Any()
    private var loaded: LoadedModel? = null

    /** Model for live playback, loaded on first use and kept until the choice changes. */
    internal fun liveModel(): LoadedModel? = synchronized(lock) {
        val s = settings.value
        val cur = loaded
        if (cur != null && cur.id == s.modelId && cur.threads == s.threads) return cur
        Log.i(TAG, "loading model ${s.modelId} (${s.threads} threads)")
        cur?.close(); loaded = null
        runCatching { LoadedModel.open(context, MusicModels.byId(s.modelId), s.threads) }
            .onFailure { Log.e(TAG, "Couldn't load music model", it); com.youtubly.app.core.ErrorLog.record("Music remover", it) }
            .getOrNull().also { loaded = it }
    }

    /**
     * Times one block (model + FFTs) for [model] on this phone. Uses a separate interpreter so
     * playback keeps working; one warm-up run, then the average of two.
     */
    suspend fun benchmark(model: MusicModel): Benchmark = withContext(Dispatchers.Default) {
        LoadedModel.open(context, model, settings.value.threads).use { m ->
            val sep = SpectralSeparator(m)
            val rnd = Random(1)
            val noise = FloatArray(1 shl 16) { rnd.nextFloat() * 0.6f - 0.3f }
            val src = SpectralSeparator.Source { c, i -> noise[((i * 2 + c) and 0xFFFF).toInt()] }
            sep.processBlock(0, src)
            val t0 = System.nanoTime()
            repeat(2) { sep.processBlock(it + 1L, src) }
            val sec = (System.nanoTime() - t0) / 2e9f
            Benchmark(model.id, sec, SpectralSeparator.BLOCK / 44_100f).also { store.saveBenchmark(it) }
        }
    }

    companion object { private const val TAG = "MusicRemover" }
}

/** One TFLite interpreter with its I/O buffers; implements the separator's model contract. */
internal class LoadedModel private constructor(
    val id: String,
    val threads: Int,
    private val interpreter: Interpreter,
) : SpectralSeparator.MaskModel, AutoCloseable {
    private val size = SpectralSeparator.MODEL_FRAMES * SpectralSeparator.MODEL_BINS * 2
    private val input = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())

    @Synchronized override fun run(magnitude: FloatArray, mask: FloatArray) {
        input.rewind(); input.asFloatBuffer().put(magnitude)
        output.rewind()
        interpreter.run(input, output)
        output.rewind(); output.asFloatBuffer().get(mask)
    }

    @Synchronized override fun close() = interpreter.close()

    companion object {
        fun open(context: Context, model: MusicModel, threads: Int): LoadedModel {
            val buffer: MappedByteBuffer = context.assets.openFd(model.assetPath).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
            val interp = Interpreter(buffer, Interpreter.Options().setNumThreads(threads.coerceIn(1, 8)))
            return LoadedModel(model.id, threads, interp)
        }
    }
}
