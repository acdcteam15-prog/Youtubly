package com.youtubly.app.domain

import com.youtubly.app.domain.model.FrameRef
import com.youtubly.app.domain.model.PreviewFrameSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewFramesTest {
    // 3 columns × 2 rows per sheet (non-square on purpose), one frame per 2 s, 14 frames.
    private val set = PreviewFrameSet(
        urls = listOf("s0", "s1", "s2"),
        frameWidth = 160, frameHeight = 90, totalCount = 14,
        durationPerFrameMs = 2_000, columns = 3, rows = 2
    )

    @Test
    fun `first frame is top-left of the first sheet`() {
        assertEquals(FrameRef("s0", 0, 0, 160, 90), set.frameAt(0))
    }

    @Test
    fun `column uses the column count on non-square grids`() {
        // frame 4 → sheet 0, row 1, col 1 (NewPipe's % rows would give col 0)
        assertEquals(FrameRef("s0", 160, 90, 160, 90), set.frameAt(8_000))
    }

    @Test
    fun `moves to the next sheet and clamps past the end`() {
        assertEquals(FrameRef("s1", 0, 0, 160, 90), set.frameAt(12_000)) // frame 6
        assertEquals(FrameRef("s2", 160, 0, 160, 90), set.frameAt(10_000_000)) // clamped to frame 13
    }

    @Test
    fun `invalid sets answer null`() {
        assertNull(set.copy(durationPerFrameMs = 0).frameAt(1_000))
        assertNull(set.copy(urls = emptyList()).frameAt(1_000))
    }

    @Test
    fun `best picks the largest level up to 320 px wide`() {
        val small = set.copy(frameWidth = 48, frameHeight = 27)
        val mid = set.copy(frameWidth = 160)
        val huge = set.copy(frameWidth = 640)
        assertEquals(mid, PreviewFrameSet.best(listOf(small, huge, mid)))
        assertEquals(huge, PreviewFrameSet.best(listOf(huge)))
    }
}
