package com.youtubly.app.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    @Test
    fun `push undo redo round trip`() {
        val h = EditHistory(1)
        assertFalse(h.canUndo); assertFalse(h.canRedo)
        h.push(2); h.push(3)
        assertTrue(h.undo()); assertEquals(2, h.present)
        assertTrue(h.undo()); assertEquals(1, h.present)
        assertFalse(h.undo()); assertEquals(1, h.present)
        assertTrue(h.redo()); assertEquals(2, h.present)
        assertTrue(h.redo()); assertEquals(3, h.present)
        assertFalse(h.redo())
    }

    @Test
    fun `a new edit clears redo`() {
        val h = EditHistory(1)
        h.push(2); h.undo(); h.push(5)
        assertFalse(h.canRedo)
        assertEquals(5, h.present)
        h.undo(); assertEquals(1, h.present)
    }

    @Test
    fun `no-op push is ignored`() {
        val h = EditHistory(EditState.initial(10_000))
        assertFalse(h.push(EditState.initial(10_000)))
        assertFalse(h.canUndo)
    }

    @Test
    fun `history is capped at the limit`() {
        val h = EditHistory(0, limit = 3)
        (1..10).forEach { h.push(it) }
        var undos = 0
        while (h.undo()) undos++
        assertEquals(3, undos)
        assertEquals(7, h.present)
    }
}
