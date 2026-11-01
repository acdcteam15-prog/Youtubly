package com.youtubly.app.presentation

import com.youtubly.app.domain.model.HistoryEntry
import com.youtubly.app.domain.model.Video
import com.youtubly.app.presentation.history.HistoryViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryGroupingTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 9, 24)

    private fun entry(id: String, day: LocalDate, hour: Int) = HistoryEntry(
        video = Video(id, "u", id, "c", null, null, 0, 0, null),
        watchedAt = day.atTime(hour, 0).toInstant(zone).toEpochMilli(),
        progress = 0f
    )

    @Test
    fun `groups into Today, Yesterday and dated days, newest first`() {
        val days = HistoryViewModel.groupByDay(
            listOf(
                entry("old", today.minusDays(5), 9),
                entry("t1", today, 8),
                entry("y", today.minusDays(1), 22),
                entry("t2", today, 20)
            ),
            zone, today
        )
        assertEquals(3, days.size)
        assertEquals("Today", days[0].label)
        assertEquals(listOf("t2", "t1"), days[0].entries.map { it.video.id })
        assertEquals("Yesterday", days[1].label)
        assertEquals(listOf("old"), days[2].entries.map { it.video.id })
    }
}
