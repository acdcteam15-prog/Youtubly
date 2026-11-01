package com.youtubly.app.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimRulesTest {
    private val d = 60_000L
    private val full = TimeRange(0, d)

    @Test
    fun `start moves freely inside the clip`() {
        assertEquals(TimeRange(15_000, d), TrimRules.move(full, TrimHandle.START, 15_000, d))
    }

    @Test
    fun `start cannot pass end minus the minimum length`() {
        val r = TimeRange(0, 10_000)
        assertEquals(TimeRange(9_500, 10_000), TrimRules.move(r, TrimHandle.START, 20_000, d))
    }

    @Test
    fun `start clamps at zero`() {
        assertEquals(TimeRange(0, d), TrimRules.move(TimeRange(5_000, d), TrimHandle.START, -3_000, d))
    }

    @Test
    fun `end clamps to duration and to start plus minimum`() {
        val r = TimeRange(10_000, 20_000)
        assertEquals(TimeRange(10_000, d), TrimRules.move(r, TrimHandle.END, 99_000, d))
        assertEquals(TimeRange(10_000, 10_500), TrimRules.move(r, TrimHandle.END, 0, d))
    }

    @Test
    fun `moving one handle never moves the other`() {
        val r = TimeRange(10_000, 20_000)
        for (t in listOf(-1L, 0L, 9_999L, 10_000L, 19_600L, 30_000L, d + 5)) {
            assertEquals(20_000, TrimRules.move(r, TrimHandle.START, t, d).endMs)
            assertEquals(10_000, TrimRules.move(r, TrimHandle.END, t, d).startMs)
        }
    }

    @Test
    fun `stale range longer than the video is repaired, not thrown`() {
        val stale = TimeRange(55_000, 90_000)
        val moved = TrimRules.move(stale, TrimHandle.END, 80_000, d)
        assertEquals(TimeRange(55_000, d), moved)
        val movedStart = TrimRules.move(TimeRange(70_000, 90_000), TrimHandle.START, 65_000, d)
        assertEquals(TimeRange(59_500, d), movedStart)
    }

    @Test
    fun `tiny and zero durations never throw`() {
        for (dur in listOf(0L, 1L, 300L, 500L, 999L)) {
            for (h in TrimHandle.entries) for (t in listOf(-10L, 0L, 250L, 5_000L)) {
                val r = TrimRules.move(TimeRange(0, dur), h, t, dur)
                assertTrue(r.startMs >= 0 && r.endMs <= dur && r.endMs >= r.startMs)
            }
        }
    }

    @Test
    fun `canEdit needs at least a second`() {
        assertFalse(TrimRules.canEdit(999))
        assertTrue(TrimRules.canEdit(1_000))
    }

    @Test
    fun `nudge steps by the delta and respects limits`() {
        val r = TimeRange(1_000, 5_000)
        assertEquals(TimeRange(1_100, 5_000), TrimRules.nudge(r, TrimHandle.START, 100, d))
        assertEquals(TimeRange(1_000, 4_900), TrimRules.nudge(r, TrimHandle.END, -100, d))
        assertEquals(TimeRange(0, 5_000), TrimRules.nudge(TimeRange(50, 5_000), TrimHandle.START, -100, d))
    }

    @Test
    fun `playFrom restarts outside or at the very end of the selection`() {
        val r = TimeRange(10_000, 20_000)
        assertEquals(10_000, TrimRules.playFrom(r, 2_000))
        assertEquals(15_000, TrimRules.playFrom(r, 15_000))
        assertEquals(10_000, TrimRules.playFrom(r, 19_900))
        assertEquals(10_000, TrimRules.playFrom(r, 25_000))
    }

    @Test
    fun `nearest handle`() {
        val r = TimeRange(10_000, 20_000)
        assertEquals(TrimHandle.START, TrimRules.nearestHandle(r, 12_000))
        assertEquals(TrimHandle.END, TrimRules.nearestHandle(r, 18_000))
        assertEquals(TrimHandle.START, TrimRules.nearestHandle(r, 15_000))
        assertEquals(TrimHandle.START, TrimRules.nearestHandle(r, 1_000))
        assertEquals(TrimHandle.END, TrimRules.nearestHandle(r, 40_000))
        // Minimum-length selection: dragging right of it grabs END, left grabs START.
        val tiny = TimeRange(10_000, 10_500)
        assertEquals(TrimHandle.END, TrimRules.nearestHandle(tiny, 10_600))
        assertEquals(TrimHandle.START, TrimRules.nearestHandle(tiny, 9_900))
    }
}
