package com.youtubly.app.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentsTest {
    private fun r(a: Long, b: Long) = TimeRange(a, b)

    @Test
    fun `normalize merges overlaps and touching ranges`() {
        assertEquals(listOf(r(0, 30), r(40, 50)), Segments.normalize(listOf(r(40, 50), r(10, 30), r(0, 12), r(30, 30))))
        assertEquals(listOf(r(0, 20)), Segments.normalize(listOf(r(0, 10), r(10, 20))))
    }

    @Test
    fun `subtract keeps the gaps in order`() {
        assertEquals(listOf(r(0, 1_000), r(2_000, 5_000)), Segments.subtract(r(0, 5_000), listOf(r(1_000, 2_000))))
        assertEquals(listOf(r(1_000, 5_000)), Segments.subtract(r(0, 5_000), listOf(r(0, 1_000))))
        assertEquals(listOf(r(0, 4_000)), Segments.subtract(r(0, 5_000), listOf(r(4_000, 9_000))))
        assertEquals(listOf(r(500, 700), r(900, 1_000)), Segments.subtract(r(500, 1_000), listOf(r(0, 500), r(700, 900))))
    }

    @Test
    fun `cuts outside the base are ignored and slivers dropped`() {
        assertEquals(listOf(r(1_000, 2_000)), Segments.subtract(r(1_000, 2_000), listOf(r(0, 500), r(3_000, 4_000))))
        // 50 ms left between two cuts → dropped
        assertEquals(listOf(r(0, 1_000), r(1_550, 3_000)), Segments.subtract(r(0, 3_000), listOf(r(1_000, 1_500), r(1_550, 1_550 + 0), r(1_500, 1_550))))
        assertEquals(listOf(r(0, 1_000), r(2_000, 3_000)), Segments.subtract(r(0, 3_000), listOf(r(1_000, 1_500), r(1_550, 2_000))))
        assertEquals(emptyList<TimeRange>(), Segments.subtract(r(0, 1_000), listOf(r(0, 1_000))))
    }

    @Test
    fun `addCut clamps to the selection and merges`() {
        val s = EditState(r(1_000, 10_000))
        val (a, p1) = CutRules.addCut(s, 5_000, 3_000) // reversed marks
        assertNull(p1)
        assertEquals(listOf(r(3_000, 5_000)), a!!.cuts)
        val (b, _) = CutRules.addCut(a, 4_000, 20_000)
        assertEquals(listOf(r(3_000, 10_000)), b!!.cuts)
        assertEquals(listOf(r(1_000, 3_000)), b.keep)
    }

    @Test
    fun `addCut refusals`() {
        val s = EditState(r(1_000, 10_000))
        assertEquals(CutProblem.OUTSIDE_SELECTION, CutRules.addCut(s, 0, 900).second)
        assertEquals(CutProblem.TOO_SHORT, CutRules.addCut(s, 2_000, 2_050).second)
        assertEquals(CutProblem.NOTHING_LEFT, CutRules.addCut(s, 1_000, 9_800).second)
    }

    @Test
    fun `removeCut and unchanged detection`() {
        val s = EditState(r(0, 10_000), listOf(r(1_000, 2_000), r(5_000, 6_000)))
        assertEquals(listOf(r(5_000, 6_000)), CutRules.removeCut(s, 0).cuts)
        assertEquals(s, CutRules.removeCut(s, 7))
        assertFalse(s.isUnchanged(10_000))
        assertTrue(EditState(r(0, 10_000)).isUnchanged(10_000))
        // A cut entirely outside the trim doesn't count as a change on its own.
        assertFalse(EditState(r(0, 9_000), listOf(r(9_500, 9_900))).isUnchanged(10_000))
    }
}
