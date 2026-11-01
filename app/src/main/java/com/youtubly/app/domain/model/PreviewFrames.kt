package com.youtubly.app.domain.model

/**
 * One storyboard level for seek-bar previews: a series of sheet images, each a
 * [columns]×[rows] grid of [frameWidth]×[frameHeight] frames, one frame every
 * [durationPerFrameMs].
 */
data class PreviewFrameSet(
    val urls: List<String>,
    val frameWidth: Int,
    val frameHeight: Int,
    val totalCount: Int,
    val durationPerFrameMs: Int,
    val columns: Int,
    val rows: Int
) {
    /** Where the frame for [positionMs] lives, or null if this set can't answer. */
    fun frameAt(positionMs: Long): FrameRef? {
        if (urls.isEmpty() || durationPerFrameMs <= 0 || columns <= 0 || rows <= 0 || totalCount <= 0) return null
        val perSheet = columns * rows
        val absolute = (positionMs.coerceAtLeast(0) / durationPerFrameMs).toInt().coerceAtMost(totalCount - 1)
        val sheet = absolute / perSheet
        val url = urls.getOrNull(sheet) ?: return null
        val relative = absolute % perSheet
        // NewPipe's Frameset.getFrameBoundsAt uses `% framesPerPageY` for the column, which is
        // wrong for non-square grids — computed here with the column count instead.
        val col = relative % columns
        val row = relative / columns
        return FrameRef(url, col * frameWidth, row * frameHeight, frameWidth, frameHeight)
    }

    companion object {
        /**
         * The sharpest usable level: largest frames up to [maxWidth] px wide (bigger sheets cost
         * data for little gain on a ~160 dp preview). Falls back to the smallest level.
         */
        fun best(sets: List<PreviewFrameSet>, maxWidth: Int = 320): PreviewFrameSet? {
            val usable = sets.filter { it.urls.isNotEmpty() && it.durationPerFrameMs > 0 && it.frameWidth > 0 && it.frameHeight > 0 }
            return usable.filter { it.frameWidth <= maxWidth }.maxByOrNull { it.frameWidth }
                ?: usable.minByOrNull { it.frameWidth }
        }
    }
}

/** A frame inside a sheet image: pixel rectangle ([left], [top], [width], [height]) of [url]. */
data class FrameRef(val url: String, val left: Int, val top: Int, val width: Int, val height: Int)
