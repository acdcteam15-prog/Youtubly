package com.youtubly.app.domain

import com.youtubly.app.domain.audio.EqPreset
import com.youtubly.app.domain.download.CleanerGroup
import com.youtubly.app.domain.download.DownloadRules
import com.youtubly.app.domain.download.SavedSort
import com.youtubly.app.domain.download.StorageCleaner
import com.youtubly.app.domain.download.sortedFor
import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class NewFeaturesTest {

    // ---------- equalizer ----------
    @Test fun `speech preset lifts consonant range and cuts rumble`() {
        assertTrue(EqPreset.SPEECH.gainDb(2500f) > 3f)
        assertTrue(EqPreset.SPEECH.gainDb(60f) < -3f)
        assertEquals(0f, EqPreset.OFF.gainDb(1000f), 0f)
    }

    @Test fun `gain is interpolated between points and clamped to the band range`() {
        val mid = EqPreset.BASS_CUT.gainDb(95f)   // between 60 Hz (-9) and 150 Hz (-6)
        assertTrue(mid > -9f && mid < -6f)
        // Equalizer API reports milli-Hz; the result is clamped to what the device allows.
        assertEquals(-1500, EqPreset.BASS_CUT.bandLevelMb(60_000, -1500, 1500))
        assertEquals(0, EqPreset.OFF.bandLevelMb(60_000, -1500, 1500))
    }

    // ---------- download window ----------
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(hour: Int, min: Int = 0): Long = Calendar.getInstance(utc).apply {
        clear(); set(2026, Calendar.OCTOBER, 1, hour, min, 0)
    }.timeInMillis

    @Test fun `no delay when the schedule is off or already inside the window`() {
        assertEquals(0L, DownloadRules.delayUntilWindowMs(DownloadRules(), at(12), utc))
        val r = DownloadRules(scheduleEnabled = true, startHour = 1, endHour = 7)
        assertEquals(0L, DownloadRules.delayUntilWindowMs(r, at(3), utc))
    }

    @Test fun `waits until the window opens, today or tomorrow`() {
        val r = DownloadRules(scheduleEnabled = true, startHour = 1, endHour = 7)
        assertEquals(13 * 3600_000L, DownloadRules.delayUntilWindowMs(r, at(12), utc))      // 12:00 → 01:00 next day
        val late = DownloadRules(scheduleEnabled = true, startHour = 22, endHour = 6)
        assertEquals(10 * 3600_000L, DownloadRules.delayUntilWindowMs(late, at(12), utc))   // 12:00 → 22:00
    }

    @Test fun `window that wraps past midnight counts both sides as inside`() {
        val r = DownloadRules(scheduleEnabled = true, startHour = 22, endHour = 6)
        assertEquals(0L, DownloadRules.delayUntilWindowMs(r, at(23), utc))
        assertEquals(0L, DownloadRules.delayUntilWindowMs(r, at(2), utc))
        assertTrue(DownloadRules.delayUntilWindowMs(r, at(6), utc) > 0)   // end hour itself is outside
    }

    @Test fun `queued hint names the active rules`() {
        assertNull(DownloadRules().queuedHint())
        assertEquals("Waiting for Wi-Fi · 01:00–07:00 · battery ≥ 30%", DownloadRules(true, 30, true, 1, 7).queuedHint())
    }

    // ---------- storage cleaner ----------
    private fun item(
        id: String, video: String = id, size: Long = 100, watched: Int = 0, created: Long = 1,
        status: DownloadStatus = DownloadStatus.COMPLETED, title: String = id
    ) = DownloadItem(id, video, title, null, "/f/$id", 100, status, false, totalBytes = size, watchedPercent = watched, createdAt = created)

    @Test fun `sort by size keeps unfinished transfers on top`() {
        val list = listOf(item("a", size = 10), item("b", size = 500), item("c", size = 50, status = DownloadStatus.RUNNING))
        assertEquals(listOf("c", "b", "a"), list.sortedFor(SavedSort.LARGEST).map { it.id })
        assertEquals(listOf("c", "a", "b"), list.sortedFor(SavedSort.NAME).map { it.id })
    }

    @Test fun `cleaner finds duplicates keeping the largest copy`() {
        val now = 100L * 24 * 3600_000
        val groups = StorageCleaner.groups(listOf(item("hd", video = "v", size = 900), item("sd", video = "v", size = 100)), now)
        val dupes = groups.first { it.kind == CleanerGroup.Kind.DUPLICATES }
        assertEquals(listOf("sd"), dupes.items.map { it.id })
    }

    @Test fun `cleaner groups watched and stale, never the playing item, no overlaps`() {
        val now = 100L * 24 * 3600_000
        val all = listOf(
            item("done", watched = 100, created = now - 1000),
            item("old", watched = 0, created = now - 40L * 24 * 3600_000),
            item("fresh", watched = 0, created = now - 1000),
            item("playing", watched = 100, created = 1)
        )
        val groups = StorageCleaner.groups(all, now, excludeId = "playing")
        assertEquals(listOf("done"), groups.first { it.kind == CleanerGroup.Kind.WATCHED }.items.map { it.id })
        assertEquals(listOf("old"), groups.first { it.kind == CleanerGroup.Kind.STALE }.items.map { it.id })
        assertTrue(groups.flatMap { it.items }.none { it.id == "playing" })
    }

    // ---------- resume point ----------
    @Test fun `resume point only for partly played files`() {
        fun r(pos: Long, dur: Long) = item("x").copy(positionMs = pos, durationMs = dur).resumeAtMs
        assertEquals(60_000L, r(60_000, 600_000))
        assertNull(r(2_000, 600_000))             // barely started
        assertNull(r(595_000, 600_000))           // within the last 10 s
        assertNull(r(0, 0))                       // never played offline
        assertFalse(item("x").copy(positionMs = 590_000, durationMs = 600_000).resumeFraction < 0.95f)
    }
}
