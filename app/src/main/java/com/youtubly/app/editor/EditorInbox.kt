package com.youtubly.app.editor

import com.youtubly.app.domain.editor.EditorRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands work to the Edit tab: Saved → long-press → Edit, and Share → Youtubly with a video.
 * A hand-off object instead of a route argument, so the Edit tab keeps its normal tab
 * behaviour (saved state, restore).
 */
@Singleton
class EditorInbox @Inject constructor() {
    private val _pending = MutableStateFlow<EditorRequest?>(null)
    val pending: StateFlow<EditorRequest?> = _pending.asStateFlow()

    fun send(request: EditorRequest) { _pending.value = request }

    fun send(downloadId: String) = send(EditorRequest.OpenDownload(downloadId))

    /** Clears [request] only if it's still the pending one (a newer send isn't lost). */
    fun consume(request: EditorRequest) { _pending.compareAndSet(request, null) }
}
