package com.youtubly.app.presentation.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.youtubly.app.blur.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class PreviewState(val original: Bitmap? = null, val result: BlurOutcome? = null, val busy: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BlurSettingsViewModel @Inject constructor(
    private val engine: BlurEngine,
    private val downloader: ModelDownloader,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {
    private val store = engine.store
    val settings = engine.settings
    val modelInfo = engine.classifier.info
    /** The models packed inside this build, for the picker. */
    val bundledModels: List<BundledModel> = engine.classifier.bundledModels
    /** This build has at least one model packed inside it. */
    val hasBundledModel: Boolean = engine.classifier.hasBundled
    /** Id of the packed model that is installed now, or null when a custom file (or none) is. */
    val activeBundledId: StateFlow<String?> = engine.classifier.activeBundledId
    /** True while a packed model is being swapped in. */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching
    /** Checksum of the installed model; recomputed whenever the model changes. */
    val modelSha256: StateFlow<String?> = engine.classifier.info
        .mapLatest { if (it == null) null else engine.classifier.sha256() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview
    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message

    init {
        // Re-run preview (debounced) whenever settings change → live tuning
        viewModelScope.launch { settings.drop(1).debounce(250).collect { rerunPreview() } }
    }

    private fun go(block: suspend BlurPreferencesStore.() -> Unit) { viewModelScope.launch { store.block() } }
    fun setEnabled(v: Boolean) = go { setEnabled(v) }
    fun setBlurFemale(v: Boolean) = go { setBlurFemale(v) }
    fun setBlurMale(v: Boolean) = go { setBlurMale(v) }
    fun setBlurUncertain(v: Boolean) = go { setBlurUncertain(v) }
    fun setMode(v: BlurMode) = go { setMode(v) }
    fun setStrength(v: Int) = go { setStrength(v) }
    fun setPadding(v: Int) = go { setPadding(v) }
    fun setBodyCoverage(v: com.youtubly.app.blur.BodyCoverage) = go { setBodyCoverage(v) }
    fun setCoverBackViews(v: Boolean) = go { setCoverBackViews(v) }
    fun setMinFaceSize(v: Int) = go { setMinFaceSize(v) }
    fun setGenderConfidence(v: Float) = go { setGenderConfidence(v) }
    fun setApplyToThumbnails(v: Boolean) = go { setApplyToThumbnails(v) }
    fun setApplyToPlayback(v: Boolean) = go { setApplyToPlayback(v) }
    fun setFemaleOutputIndex(v: Int) = go { setFemaleOutputIndex(v) }
    fun setFlipGender(v: Boolean) = go { setFlipGender(v) }
    fun setNormalization(v: Normalization) = go { setNormalization(v) }
    fun setScaleWithFaceSize(v: Boolean) = go { setScaleWithFaceSize(v) }
    fun resetDefaults() = go { resetToDefaults() }

    /** True while the model is downloading/validating, so the UI can disable the button. */
    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading
    /** 0..100, or -1 when unknown. */
    private val _downloadProgress = MutableStateFlow(-1)
    val downloadProgress: StateFlow<Int> = _downloadProgress

    /** Downloads the gender model from Hugging Face, validates it, installs it. */
    fun importModel() {
        if (_downloading.value) return
        viewModelScope.launch {
            _downloading.value = true; _downloadProgress.value = -1
            val download = downloader.download { _downloadProgress.value = it }
            val file = download.getOrNull()
            if (file == null) {
                _message.emit("Download failed: ${download.exceptionOrNull()?.message ?: "check your connection"}")
            } else {
                val result = engine.classifier.importFromFile(file)
                val info = result.getOrNull()
                if (info != null) {
                    _message.emit("Model loaded: ${info.inputW}×${info.inputH}, ${info.outputs} output(s)")
                    rerunPreview()
                } else {
                    _message.emit("Import failed: ${result.exceptionOrNull()?.message}")
                }
            }
            _downloading.value = false
        }
    }

    /**
     * Switches to a packed model AND applies that model's own settings (which output is "female",
     * how pixels are scaled). Doing both together is what stops men and women from being swapped when
     * the user moves between models that use different conventions.
     */
    fun chooseBundledModel(id: String) {
        if (_switching.value || activeBundledId.value == id) return
        viewModelScope.launch {
            _switching.value = true
            engine.classifier.useBundled(id)
                .onSuccess { m ->
                    store.setFemaleOutputIndex(m.femaleIndex)
                    store.setNormalization(m.normalization)
                    store.setFlipGender(false)          // a flip is per model; don't carry it over
                    _message.emit("Using ${m.title}")
                    rerunPreview()
                }
                .onFailure { _message.emit("Couldn't switch model: ${it.message}") }
            _switching.value = false
        }
    }

    /** Puts the recommended packed model back (after Remove, or after a custom file). */
    fun useBundledModel() = viewModelScope.launch {
        engine.classifier.restoreBundled()
            .onSuccess { m ->
                store.setFemaleOutputIndex(m.femaleIndex); store.setNormalization(m.normalization); store.setFlipGender(false)
                _message.emit("Built-in model restored"); rerunPreview()
            }
            .onFailure { _message.emit("Couldn't restore it: ${it.message}") }
    }

    /** Manual change from a file on the phone. */
    fun importModelFile(uri: Uri) = viewModelScope.launch {
        engine.classifier.import(uri)
            .onSuccess { _message.emit("Model loaded: ${it.inputW}×${it.inputH}, ${it.outputs} output(s)"); rerunPreview() }
            .onFailure { _message.emit("Import failed: ${it.message}") }
    }

    fun removeModel() { engine.classifier.remove(); viewModelScope.launch { _message.emit("Model removed — all faces treated as uncertain"); rerunPreview() } }

    fun loadPreview(uri: Uri) = viewModelScope.launch {
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE           // needed for pixel access
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 1280) d.setTargetSampleSize(longest / 1280)
                }
            }.getOrNull()
        } ?: return@launch _message.emit("Couldn't open image")
        _preview.value = PreviewState(original = bmp); rerunPreview()
    }

    private var job: Job? = null
    private fun rerunPreview() {
        val src = _preview.value.original ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _preview.update { it.copy(busy = true) }
            runCatching { engine.process(src, null, BlurSurface.PREVIEW, settings.value) }
                .onSuccess { r -> _preview.update { it.copy(result = r, busy = false) } }
                .onFailure { e -> if (e !is CancellationException) { _preview.update { it.copy(busy = false) }; _message.emit("Detection failed: ${e.message}") } }
        }
    }
}
