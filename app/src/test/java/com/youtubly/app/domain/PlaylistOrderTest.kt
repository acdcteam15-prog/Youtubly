package com.youtubly.app.domain

import com.youtubly.app.domain.playlist.PlaylistOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOrderTest {
    @Test fun moveDown() = assertEquals(listOf("b", "c", "a", "d"), PlaylistOrder.move(listOf("a", "b", "c", "d"), 0, 2))
    @Test fun moveUp() = assertEquals(listOf("d", "a", "b", "c"), PlaylistOrder.move(listOf("a", "b", "c", "d"), 3, 0))
    @Test fun moveOutOfRangeIsNoOp() = assertEquals(listOf("a", "b"), PlaylistOrder.move(listOf("a", "b"), 0, 5))
    @Test fun shuffleKeepsFirstAndAllItems() {
        val list = (1..20).toList()
        val s = PlaylistOrder.shuffled(list, first = 7, random = kotlin.random.Random(1))
        assertEquals(7, s.first())
        assertEquals(list.toSet(), s.toSet())
        assertEquals(list.size, s.size)
    }
    @Test fun shuffleWithoutFirstIsPermutation() {
        val s = PlaylistOrder.shuffled(listOf(1, 2, 3), random = kotlin.random.Random(3))
        assertTrue(s.sorted() == listOf(1, 2, 3))
    }
}
