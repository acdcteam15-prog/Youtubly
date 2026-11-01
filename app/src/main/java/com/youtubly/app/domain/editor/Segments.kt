package com.youtubly.app.domain.editor

/** Range arithmetic for cuts ("delete a part") and kept segments. */
object Segments {
    /** Pieces shorter than this are dropped: they'd be a stray frame or two in the output. */
    const val MIN_PIECE_MS = 100L

    /** Sorted union of [ranges] (overlapping or touching ranges merged). */
    fun normalize(ranges: List<TimeRange>): List<TimeRange> {
        val sorted = ranges.filter { it.durationMs > 0 }.sortedBy { it.startMs }
        val out = ArrayList<TimeRange>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.startMs <= last.endMs) {
                out[out.lastIndex] = TimeRange(last.startMs, maxOf(last.endMs, r.endMs))
            } else {
                out.add(r)
            }
        }
        return out
    }

    /** [base] minus [cuts], in order, without pieces shorter than [MIN_PIECE_MS]. */
    fun subtract(base: TimeRange, cuts: List<TimeRange>): List<TimeRange> {
        val out = ArrayList<TimeRange>()
        var cursor = base.startMs
        for (c in normalize(cuts)) {
            if (c.endMs <= cursor) continue
            if (c.startMs >= base.endMs) break
            if (c.startMs > cursor) out.add(TimeRange(cursor, c.startMs))
            cursor = maxOf(cursor, c.endMs)
        }
        if (cursor < base.endMs) out.add(TimeRange(cursor, base.endMs))
        return out.filter { it.durationMs >= MIN_PIECE_MS }
    }

    fun totalMs(ranges: List<TimeRange>): Long = ranges.sumOf { it.durationMs }
}

/** Why a cut was refused (shown to the user). */
enum class CutProblem { TOO_SHORT, OUTSIDE_SELECTION, NOTHING_LEFT }

object CutRules {
    /**
     * Adds the cut between [a] and [b] (either order, e.g. two playhead marks).
     * Returns the new state, or the reason it can't be done.
     */
    fun addCut(state: EditState, a: Long, b: Long): Pair<EditState?, CutProblem?> {
        val from = minOf(a, b).coerceAtLeast(state.trim.startMs)
        val to = maxOf(a, b).coerceAtMost(state.trim.endMs)
        if (to <= from) return null to CutProblem.OUTSIDE_SELECTION
        if (to - from < Segments.MIN_PIECE_MS) return null to CutProblem.TOO_SHORT
        val next = state.copy(cuts = Segments.normalize(state.cuts + TimeRange(from, to)))
        if (Segments.totalMs(next.keep) < TrimRules.MIN_CLIP_MS) return null to CutProblem.NOTHING_LEFT
        return next to null
    }

    fun removeCut(state: EditState, index: Int): EditState =
        if (index !in state.cuts.indices) state else state.copy(cuts = state.cuts.filterIndexed { i, _ -> i != index })
}
