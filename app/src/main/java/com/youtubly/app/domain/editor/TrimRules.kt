package com.youtubly.app.domain.editor

/** How trim handles may move. Every UI path (drag, nudge, "set at playhead") goes through here. */
object TrimRules {
    /** Shortest clip the editor produces. */
    const val MIN_CLIP_MS = 500L

    /** Smallest step for the fine-tune buttons. */
    const val NUDGE_MS = 100L

    /** Videos shorter than this can't be trimmed at all. */
    fun canEdit(durationMs: Long): Boolean = durationMs >= MIN_CLIP_MS * 2

    /** Moves one handle to [targetMs], clamped so the clip stays inside the video and at
     *  least [MIN_CLIP_MS] long. The other handle never moves. */
    fun move(range: TimeRange, handle: TrimHandle, targetMs: Long, durationMs: Long): TimeRange {
        val duration = durationMs.coerceAtLeast(0)
        val minLen = MIN_CLIP_MS.coerceAtMost(duration)
        // Clamp the existing range first, so a stale range (e.g. from a shorter reading of the
        // duration) can never produce inverted bounds below.
        val end0 = range.endMs.coerceIn(minLen, duration)
        val start0 = range.startMs.coerceIn(0, end0 - minLen)
        return when (handle) {
            TrimHandle.START -> TimeRange(targetMs.coerceIn(0, end0 - minLen), end0)
            TrimHandle.END -> TimeRange(start0, targetMs.coerceIn(start0 + minLen, duration))
        }
    }

    fun nudge(range: TimeRange, handle: TrimHandle, deltaMs: Long, durationMs: Long): TimeRange {
        val current = if (handle == TrimHandle.START) range.startMs else range.endMs
        return move(range, handle, current + deltaMs, durationMs)
    }

    /** Where the preview should (re)start playing from: inside the selection, or its start. */
    fun playFrom(range: TimeRange, positionMs: Long): Long =
        if (positionMs < range.startMs || positionMs >= range.endMs - 250) range.startMs else positionMs

    /** Which handle a touch at [ms] should grab: the nearer one (START on a tie, unless the
     *  touch is past the end — then END, so a collapsed-looking selection can still grow). */
    fun nearestHandle(range: TimeRange, ms: Long): TrimHandle {
        if (ms > range.endMs) return TrimHandle.END
        if (ms < range.startMs) return TrimHandle.START
        return if (ms - range.startMs <= range.endMs - ms) TrimHandle.START else TrimHandle.END
    }
}
