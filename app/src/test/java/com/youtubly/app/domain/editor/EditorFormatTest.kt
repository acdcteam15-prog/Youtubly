package com.youtubly.app.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class EditorFormatTest {
    @Test
    fun clock() {
        assertEquals("0:00", EditorFormat.clock(0))
        assertEquals("1:05", EditorFormat.clock(65_000))
        assertEquals("1:05.3", EditorFormat.clock(65_350, tenths = true))
        assertEquals("1:02:05", EditorFormat.clock(3_725_000))
        assertEquals("0:00", EditorFormat.clock(-5))
        assertEquals("0:59.9", EditorFormat.clock(59_999, tenths = true))
    }

    private val now = LocalDateTime.of(2026, 9, 25, 14, 30, 12)

    @Test
    fun `file name strips illegal characters`() {
        assertEquals("a_b_c_d_edit_20260925_143012.mp4", EditorFormat.outputFileName("a/b:c?d", now))
        assertEquals("video_edit_20260925_143012.mp4", EditorFormat.outputFileName("  ...  ", now))
        assertEquals("video_edit_20260925_143012.mp4", EditorFormat.outputFileName("", now))
        assertEquals("tab_x_edit_20260925_143012.mp4", EditorFormat.outputFileName("tab\tx", now))
    }

    @Test
    fun `file name is length capped`() {
        val name = EditorFormat.outputFileName("x".repeat(500), now)
        assertEquals(60 + "_edit_20260925_143012.mp4".length, name.length)
    }

    @Test
    fun `size estimate and space check`() {
        assertEquals(55_000_000, ExportMath.estimateOutputBytes(100_000_000, 60_000, TimeRange(0, 30_000)))
        assertEquals(0, ExportMath.estimateOutputBytes(0, 60_000, TimeRange(0, 30_000)))
        assertEquals(0, ExportMath.estimateOutputBytes(10, 0, TimeRange(0, 0)))
        assertTrue(ExportMath.hasRoomFor(100L shl 20, 300L shl 20))
        assertFalse(ExportMath.hasRoomFor(100L shl 20, 200L shl 20))
    }

    @Test
    fun `frame times sit in the middle of each slot`() {
        assertEquals(listOf(500L, 1_500L, 2_500L, 3_500L), ExportMath.frameTimesMs(4_000, 4))
        assertEquals(emptyList<Long>(), ExportMath.frameTimesMs(0, 4))
        assertEquals(emptyList<Long>(), ExportMath.frameTimesMs(4_000, 0))
        assertTrue(ExportMath.frameTimesMs(10_000, 10).all { it in 0 until 10_000 })
    }

    @Test
    fun `status parsing and progress`() {
        assertEquals(EditJobStatus.RUNNING, EditJobStatus.parse("RUNNING"))
        assertEquals(EditJobStatus.FAILED, EditJobStatus.parse("garbage"))
        assertEquals(EditJobStatus.FAILED, EditJobStatus.parse(null))
        assertEquals(99, ExportMath.exportPercent(100))
        assertEquals(0, ExportMath.exportPercent(-4))
    }

    @Test
    fun `safe range and unchanged detection`() {
        assertEquals(TimeRange(0, 0), TimeRange.safe(-5, -10))
        assertEquals(TimeRange(10, 10), TimeRange.safe(10, 3))
        assertTrue(EditState.initial(5_000).isUnchanged(5_000))
        assertFalse(EditState(TimeRange(0, 4_000)).isUnchanged(5_000))
        assertEquals(TimeRange(0, 0), EditState.initial(-1).trim)
    }
}
