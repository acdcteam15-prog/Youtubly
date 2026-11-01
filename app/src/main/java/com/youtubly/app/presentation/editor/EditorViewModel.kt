package com.youtubly.app.presentation.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.youtubly.app.data.repository.EditRepository
import com.youtubly.app.domain.editor.ClipSpec
import com.youtubly.app.domain.editor.ConvertRules
import com.youtubly.app.domain.editor.CutProblem
import com.youtubly.app.domain.editor.CutRules
import com.youtubly.app.domain.editor.EditHistory
import com.youtubly.app.domain.editor.EditJob
import com.youtubly.app.domain.editor.EditSource
import com.youtubly.app.domain.editor.EditState
import com.youtubly.app.domain.editor.EditorRequest
import com.youtubly.app.domain.editor.SnapshotFormat
import com.youtubly.app.domain.editor.ExportSpec
import com.youtubly.app.domain.editor.OutputMath
import com.youtubly.app.domain.editor.OutputSettings
import com.youtubly.app.domain.editor.Segments
import com.youtubly.app.domain.editor.TimeRange
import com.youtubly.app.domain.editor.TrimHandle
import com.youtubly.app.domain.editor.TrimRules
import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadStatus
import com.youtubly.app.domain.repository.DownloadRepository
import com.youtubly.app.editor.EditorInbox
import com.youtubly.app.editor.ExportFailed
import com.youtubly.app.editor.FrameGrabber
import com.youtubly.app.editor.SharedVideoImporter
import com.youtubly.app.editor.FrameStripCache
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** What the editor shows for the open video. [trim] is live (follows a handle while dragged). */
data class EditorSession(
    val source: EditSource,
    val trim: TimeRange,
    val cuts: List<TimeRange>,
    /** What the export keeps (trim minus cuts). */
    val keep: List<TimeRange>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    /** Same as the source: no trim, no cuts. */
    val isUnchanged: Boolean
) {
    val keptMs: Long get() = Segments.totalMs(keep)
}

data class EditorUiState(
    val loading: Boolean = false,
    val session: EditorSession? = null,
    val error: String? = null
)

@OptIn(UnstableApi::class) // SeekParameters / setSeekParameters
@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val downloads: DownloadRepository,
    private val editRepository: EditRepository,
    private val frameCache: FrameStripCache,
    private val inbox: EditorInbox,
    private val importer: SharedVideoImporter,
    private val frameGrabber: FrameGrabber,
    private val userPreferences: com.youtubly.app.data.local.datastore.UserPreferences
) : ViewModel() {

    /** Preview player, separate from the app's main player so editing never replaces what's
     *  playing there. It takes audio focus, so the main player pauses when a preview plays. */
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true
        )
        playWhenReady = false
    }

    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui.asStateFlow()

    private val _frames = MutableStateFlow<List<Bitmap?>>(emptyList())
    val frames: StateFlow<List<Bitmap?>> = _frames.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** First mark of a cut ("Cut from here"), waiting for the second. */
    private val _pendingCutMs = MutableStateFlow<Long?>(null)
    val pendingCutMs: StateFlow<Long?> = _pendingCutMs.asStateFlow()

    /** Resolution / bitrate for the next export (applies to single videos and merges). */
    private val _output = MutableStateFlow(OutputSettings())
    val output: StateFlow<OutputSettings> = _output.asStateFlow()

    private val _snapshotFormat = MutableStateFlow(SnapshotFormat.JPG)
    val snapshotFormat: StateFlow<SnapshotFormat> = _snapshotFormat.asStateFlow()
    private var snapshotting = false

    /** Merge mode: selected download ids in merge order; null when not merging. */
    private val _merge = MutableStateFlow<List<String>?>(null)
    val merge: StateFlow<List<String>?> = _merge.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events

    val jobs: StateFlow<List<EditJob>> = editRepository.observeJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Saved items that can be opened: finished and with a file (video or audio-only). */
    val candidates: StateFlow<List<DownloadItem>> = downloads.observeDownloads()
        .map { list -> list.filter { it.status == DownloadStatus.COMPLETED && it.filePath != null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var source: EditSource? = null
    private var sourceBytes: Long = 0
    private var history: EditHistory<EditState>? = null
    /** Live range while a handle is being dragged; committed to [history] on release. */
    private var draft: TimeRange? = null
    private var dragTargetMs: Long? = null
    private var loadJob: Job? = null
    private var framesJob: Job? = null
    private var enqueueing = false

    init {
        // Start from the last export's resolution / bitrate.
        viewModelScope.launch {
            val h = userPreferences.editorMaxHeight.first()
            val k = userPreferences.editorBitrateKbps.first()
            if (_output.value.isOriginal) _output.value = OutputSettings(h, k)
        }
        viewModelScope.launch {
            inbox.pending.filterNotNull().collect { request ->
                inbox.consume(request)
                when (request) {
                    is EditorRequest.OpenDownload -> open(request.downloadId)
                    is EditorRequest.ImportShared -> importShared(request.uri)
                    is EditorRequest.OpenImported -> openImported(request.path, request.title)
                }
            }
        }
        // Playhead, and the preview plays only what the export keeps: it jumps over cuts and
        // stops at the end of the selection.
        viewModelScope.launch {
            while (true) {
                tick()
                delay(if (player.isPlaying) 40 else 200)
            }
        }
    }

    private fun tick() {
        val state = currentState() ?: return
        if (player.isPlaying) {
            val pos = player.currentPosition
            val cut = state.cuts.firstOrNull { pos >= it.startMs && pos < it.endMs }
            when {
                pos >= state.trim.endMs || (cut != null && cut.endMs >= state.trim.endMs) -> {
                    player.pause()
                    player.seekTo(state.keep.firstOrNull()?.startMs ?: state.trim.startMs)
                }
                cut != null -> player.seekTo(cut.endMs)
            }
        }
        _isPlaying.value = player.isPlaying
        if (dragTargetMs == null) _positionMs.value = player.currentPosition
    }

    // ---- Opening / closing ----

    fun open(downloadId: String) {
        loadJob?.cancel()
        _merge.value = null
        loadJob = viewModelScope.launch {
            closeInternal()
            _ui.value = EditorUiState(loading = true)
            val item = downloads.observeDownloads().first().firstOrNull { it.id == downloadId }
            val path = item?.filePath
            val problem = when {
                item == null || path == null -> "This video isn't in Saved anymore"
                item.status != DownloadStatus.COMPLETED -> "Wait for the download to finish first"
                !File(path).isFile -> "The saved file is missing — try downloading it again"
                else -> null
            }
            if (problem != null || item == null || path == null) {
                _ui.value = EditorUiState(error = problem ?: "Couldn't open this video")
                return@launch
            }
            openFile(item.id, path, item.title, item.thumbnailUrl)
        }
    }

    /** Share → Youtubly: copy the shared video into app storage, then open it like a download. */
    private fun openImported(path: String, title: String) {
        loadJob?.cancel()
        _merge.value = null
        loadJob = viewModelScope.launch {
            closeInternal()
            _ui.value = EditorUiState(loading = true)
            openFile("shared:${File(path).name}", path, title, null)
        }
    }

    private fun importShared(uri: String) {
        loadJob?.cancel()
        _merge.value = null
        loadJob = viewModelScope.launch {
            closeInternal()
            _ui.value = EditorUiState(loading = true)
            val imported = try {
                importer.import(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _ui.value = EditorUiState(error = (t as? ExportFailed)?.message ?: "Couldn't import the shared video")
                return@launch
            }
            openFile("shared:${File(imported.path).name}", imported.path, imported.title, null)
        }
    }

    /** Runs inside [loadJob]. */
    private suspend fun openFile(sourceId: String, path: String, title: String, thumbnailUrl: String?) {
        run {
            val probe = withContext(Dispatchers.IO) { probe(path) }
            val error = when {
                probe == null -> "Couldn't read this file"
                !probe.hasVideo && !probe.hasAudio -> "This file has no video or audio"
                !TrimRules.canEdit(probe.durationMs) -> "This is too short to edit"
                else -> null
            }
            if (error != null || probe == null) {
                _ui.value = EditorUiState(error = error ?: "Couldn't read this file")
                return
            }
            val src = EditSource(sourceId, path, title, thumbnailUrl, probe.durationMs, probe.width, probe.height, audioOnly = !probe.hasVideo)
            source = src
            sourceBytes = withContext(Dispatchers.IO) { File(path).length() }
            history = EditHistory(EditState.initial(src.durationMs))
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            player.prepare()
            player.seekTo(0)
            _positionMs.value = 0
            publish()
            if (!src.audioOnly) {
                framesJob = viewModelScope.launch { frameCache.frames(path, src.durationMs, FRAME_COUNT).collect { _frames.value = it } }
            } else {
                _frames.value = List(FRAME_COUNT) { null }
            }
        }
    }

    fun close() {
        loadJob?.cancel()
        closeInternal()
        _ui.value = EditorUiState()
    }

    fun dismissError() {
        if (_ui.value.error != null) _ui.value = _ui.value.copy(error = null)
    }

    private fun closeInternal() {
        framesJob?.cancel()
        framesJob = null
        player.stop()
        player.clearMediaItems()
        source = null
        sourceBytes = 0
        history = null
        draft = null
        dragTargetMs = null
        _pendingCutMs.value = null
        _frames.value = emptyList()
        _isPlaying.value = false
        _positionMs.value = 0
    }

    // ---- Playback ----

    fun togglePlay() {
        val state = currentState() ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            val keep = state.keep
            val pos = player.currentPosition
            // Continue from here if inside a kept part (and not at its very end), else from the start.
            val inside = keep.firstOrNull { pos >= it.startMs && pos < it.endMs - 250 }
            player.seekTo(if (inside != null) pos else keep.firstOrNull()?.startMs ?: state.trim.startMs)
            player.play()
        }
        _isPlaying.value = player.isPlaying
    }

    fun pause() {
        player.pause()
        _isPlaying.value = false
    }

    fun seekTo(ms: Long) {
        val src = source ?: return
        val target = ms.coerceIn(0, src.durationMs)
        player.seekTo(target)
        _positionMs.value = target
    }

    // ---- Trim ----

    fun onHandleDrag(handle: TrimHandle, ms: Long) {
        val src = source ?: return
        val h = history ?: return
        if (draft == null) {
            pause()
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC) // fast scrubbing while dragging
        }
        val next = TrimRules.move(draft ?: h.present.trim, handle, ms, src.durationMs)
        draft = next
        val at = if (handle == TrimHandle.START) next.startMs else next.endMs
        dragTargetMs = at
        player.seekTo(at)
        _positionMs.value = at
        publish()
    }

    fun onHandleDragEnd() {
        val d = draft ?: return
        val h = history ?: return
        val at = dragTargetMs
        draft = null
        dragTargetMs = null
        player.setSeekParameters(SeekParameters.EXACT)
        if (at != null) player.seekTo(at) // land on the exact frame the handle points at
        commitTrim(h.present, d)
    }

    fun nudge(handle: TrimHandle, deltaMs: Long) {
        val src = source ?: return
        val h = history ?: return
        val next = TrimRules.nudge(h.present.trim, handle, deltaMs, src.durationMs)
        commitTrim(h.present, next)
        seekTo(if (handle == TrimHandle.START) next.startMs else next.endMs)
    }

    fun setHandleAtPlayhead(handle: TrimHandle) {
        val src = source ?: return
        val h = history ?: return
        commitTrim(h.present, TrimRules.move(h.present.trim, handle, player.currentPosition, src.durationMs))
    }

    /** A trim change keeps the cuts, unless it would leave less than the minimum clip. */
    private fun commitTrim(state: EditState, trim: TimeRange) {
        val withCuts = state.copy(trim = trim)
        commit(if (Segments.totalMs(withCuts.keep) >= TrimRules.MIN_CLIP_MS) withCuts else withCuts.copy(cuts = emptyList()))
    }

    // ---- Cuts (delete a part) ----

    /** First tap marks where the removed part starts, second tap where it ends. */
    fun markCut() {
        val state = currentState() ?: return
        pause()
        val pos = player.currentPosition
        val first = _pendingCutMs.value
        if (first == null) {
            if (pos < state.trim.startMs || pos > state.trim.endMs) {
                _events.tryEmit("Move the playhead inside the selection first")
                return
            }
            _pendingCutMs.value = pos
            return
        }
        _pendingCutMs.value = null
        val (next, problem) = CutRules.addCut(state, first, pos)
        when {
            next != null -> commit(next)
            problem == CutProblem.TOO_SHORT -> _events.tryEmit("Move the playhead further to mark the part to remove")
            problem == CutProblem.NOTHING_LEFT -> _events.tryEmit("That would remove everything")
            else -> _events.tryEmit("That part is outside the selection")
        }
    }

    fun cancelCutMark() { _pendingCutMs.value = null }

    fun removeCut(index: Int) {
        val state = currentState() ?: return
        commit(CutRules.removeCut(state, index))
    }

    // ---- History ----

    fun undo() { if (draft == null && history?.undo() == true) { _pendingCutMs.value = null; publish(); keepPlayheadInside() } }
    fun redo() { if (draft == null && history?.redo() == true) { _pendingCutMs.value = null; publish(); keepPlayheadInside() } }

    fun resetEdits() {
        val src = source ?: return
        _pendingCutMs.value = null
        commit(EditState.initial(src.durationMs))
    }

    private fun commit(state: EditState) {
        history?.push(state)
        publish()
    }

    private fun keepPlayheadInside() {
        val trim = currentState()?.trim ?: return
        val pos = player.currentPosition
        if (pos < trim.startMs || pos > trim.endMs) seekTo(trim.startMs)
    }

    /** Present state with the live drag applied. */
    private fun currentState(): EditState? {
        val present = history?.present ?: return null
        return draft?.let { present.copy(trim = it) } ?: present
    }

    private fun publish() {
        val src = source ?: return
        val h = history ?: return
        val state = currentState() ?: return
        _ui.value = EditorUiState(
            session = EditorSession(
                source = src,
                trim = state.trim,
                cuts = state.cuts,
                keep = state.keep,
                canUndo = h.canUndo && draft == null,
                canRedo = h.canRedo && draft == null,
                isUnchanged = state.isUnchanged(src.durationMs)
            )
        )
    }

    // ---- Output settings (advanced) ----

    fun setMaxHeight(height: Int?) { _output.value = _output.value.copy(maxHeight = height); rememberOutput() }

    fun setVideoBitrate(kbps: Int?) {
        _output.value = _output.value.copy(
            videoBitrateKbps = kbps?.coerceIn(OutputSettings.MIN_KBPS, OutputSettings.MAX_KBPS)
        )
        rememberOutput()
    }

    private fun rememberOutput() {
        val o = _output.value
        viewModelScope.launch { userPreferences.setEditorOutput(o.maxHeight, o.videoBitrateKbps) }
    }

    /** Saves just the sound of the current selection (trim + cuts) as .m4a. */
    fun extractAudio() {
        val session = _ui.value.session ?: return
        if (enqueueing || draft != null || session.source.audioOnly) return
        if (session.keptMs < TrimRules.MIN_CLIP_MS) { _events.tryEmit("Selection is too short"); return }
        val spec = ExportSpec(
            clips = listOf(ClipSpec(session.source.path, session.source.durationMs, session.keep, session.source.width, session.source.height)),
            audioOnly = true
        )
        enqueue(session.source.downloadId, session.source.title, session.source.thumbnailUrl, spec)
    }

    /** Estimated size of the current single-video export. */
    fun estimateBytes(session: EditorSession, output: OutputSettings): Long =
        OutputMath.estimateBytes(specFor(session, output), listOf(sourceBytes))

    private fun specFor(session: EditorSession, output: OutputSettings) = ExportSpec(
        clips = listOf(ClipSpec(session.source.path, session.source.durationMs, session.keep, session.source.width, session.source.height)),
        output = if (session.source.audioOnly) OutputSettings() else output,
        audioOnly = session.source.audioOnly
    )

    // ---- Export ----

    /** Export allowed when something changes: an edit, or at least a new resolution/bitrate. */
    fun canExport(session: EditorSession, output: OutputSettings): Boolean =
        (!session.isUnchanged || (!output.isOriginal && !session.source.audioOnly)) && session.keptMs >= TrimRules.MIN_CLIP_MS

    fun export() {
        val session = _ui.value.session ?: return
        if (enqueueing || draft != null) return
        val output = _output.value
        if (!canExport(session, output)) {
            _events.tryEmit("Trim, cut or choose a new size first")
            return
        }
        enqueue(session.source.downloadId, session.source.title, session.source.thumbnailUrl, specFor(session, output))
    }

    private fun enqueue(downloadId: String, title: String, thumb: String?, spec: ExportSpec, onDone: () -> Unit = {}) {
        enqueueing = true
        viewModelScope.launch {
            try {
                editRepository.enqueue(downloadId, title, thumb, spec)
                _events.emit("Added to exports — you can leave the app, it keeps going")
                onDone()
            } catch (t: Throwable) {
                _events.emit("Couldn't add the export: ${t.message ?: "unknown error"}")
            } finally {
                enqueueing = false
            }
        }
    }

    // ---- Convert ----

    /** WebM/3GP video → MP4, non-M4A audio → M4A. */
    fun needsConversion(session: EditorSession): Boolean =
        ConvertRules.needsConversion(session.source.path, session.source.audioOnly)

    /** Converts the whole file as it is (ignores the trim/cuts and size settings). */
    fun convertWholeFile() {
        val src = source ?: return
        if (enqueueing) return
        val spec = ExportSpec(
            clips = listOf(ClipSpec(src.path, src.durationMs, listOf(TimeRange(0, src.durationMs)), src.width, src.height)),
            audioOnly = src.audioOnly
        )
        enqueue(src.downloadId, src.title, src.thumbnailUrl, spec)
    }

    // ---- Snapshot ----

    fun setSnapshotFormat(format: SnapshotFormat) { _snapshotFormat.value = format }

    /** Saves the frame under the playhead to Pictures/Youtubly. */
    fun takeSnapshot() {
        val src = source ?: return
        if (src.audioOnly || snapshotting) return
        pause()
        val position = player.currentPosition.coerceIn(0, src.durationMs)
        snapshotting = true
        viewModelScope.launch {
            try {
                frameGrabber.saveFrame(src.path, src.title, position, _snapshotFormat.value)
                _events.emit("Frame saved to Pictures/Youtubly")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _events.emit((t as? ExportFailed)?.message ?: "Couldn't save the frame")
            } finally {
                snapshotting = false
            }
        }
    }

    // ---- Merge ----

    fun startMerge() {
        close()
        _merge.value = emptyList()
    }

    fun cancelMerge() { _merge.value = null }

    /** Tap order = merge order; tapping a selected video removes it. */
    fun toggleMerge(id: String) {
        val current = _merge.value ?: return
        _merge.value = if (id in current) current - id else current + id
    }

    fun moveInMerge(id: String, up: Boolean) {
        val current = _merge.value?.toMutableList() ?: return
        val i = current.indexOf(id)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in current.indices) return
        current[i] = current[j].also { current[j] = current[i] }
        _merge.value = current
    }

    fun exportMerge() {
        val ids = _merge.value ?: return
        if (enqueueing) return
        if (ids.size < 2) {
            _events.tryEmit("Pick at least two videos")
            return
        }
        enqueueing = true
        viewModelScope.launch {
            val byId = candidates.value.filter { !it.isAudioOnly }.associateBy { it.id }
            val items = ids.mapNotNull { byId[it] }
            if (items.size != ids.size) {
                enqueueing = false
                _merge.value = items.map { it.id }
                _events.emit("Some videos are no longer in Saved")
                return@launch
            }
            val clips = withContext(Dispatchers.IO) {
                items.map { item ->
                    val path = item.filePath ?: return@map null
                    val p = probe(path)?.takeIf { it.hasVideo && it.durationMs > 0 } ?: return@map null
                    ClipSpec(path, p.durationMs, listOf(TimeRange(0, p.durationMs)), p.width, p.height)
                }
            }
            enqueueing = false
            val bad = clips.indexOfFirst { it == null }
            if (bad >= 0) {
                _events.emit("Couldn't read \"${items[bad].title}\"")
                return@launch
            }
            val first = items.first()
            val title = "${first.title} + ${items.size - 1} more"
            enqueue(first.id, title, first.thumbnailUrl, ExportSpec(clips.filterNotNull(), _output.value)) {
                _merge.value = null
            }
        }
    }

    // ---- Queue ----

    fun cancelJob(id: String) { viewModelScope.launch { editRepository.cancel(id) } }
    fun retryJob(id: String) { viewModelScope.launch { editRepository.retry(id) } }
    fun removeJob(id: String) { viewModelScope.launch { editRepository.remove(id) } }
    fun clearFinished() { viewModelScope.launch { editRepository.clearFinished() } }

    override fun onCleared() {
        player.release()
    }

    private data class Probe(val durationMs: Long, val hasVideo: Boolean, val hasAudio: Boolean, val width: Int, val height: Int)

    private fun probe(path: String): Probe? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotated = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0
            Probe(duration, hasVideo, hasAudio, if (rotated) h else w, if (rotated) w else h)
        } catch (e: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val FRAME_COUNT = 10
    }
}
