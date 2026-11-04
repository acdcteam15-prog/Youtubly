package com.youtubly.app.domain

import com.youtubly.app.domain.download.AutoDelete
import com.youtubly.app.domain.download.AutoDownloadPlanner
import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadStatus
import com.youtubly.app.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDownloadTest {
    private fun v(id: String) = Video(id, "https://youtu.be/$id", id, "c", null, null, 60, 0, null)

    @Test fun `the first look only remembers, it never downloads the backlog`() {
        val plan = AutoDownloadPlanner.plan(listOf(v("a"), v("b")), seen = null)
        assertTrue(plan.fresh.isEmpty())
        assertEquals(listOf("a", "b"), plan.seenAfter)
    }

    @Test fun `only videos not seen before are new, oldest first`() {
        val plan = AutoDownloadPlanner.plan(listOf(v("n2"), v("n1"), v("old")), seen = listOf("old"))
        assertEquals(listOf("n1", "n2"), plan.fresh.map { it.id })
        assertEquals(listOf("n2", "n1", "old"), plan.seenAfter)
    }

    @Test fun `nothing new means nothing to do and the memory stays small`() {
        assertTrue(AutoDownloadPlanner.plan(listOf(v("a")), seen = listOf("a")).fresh.isEmpty())
        val many = (1..100).map { "s$it" }
        assertEquals(AutoDownloadPlanner.REMEMBER, AutoDownloadPlanner.plan(listOf(v("x")), many).seenAfter.size)
    }

    @Test fun `channel keys turn back into addresses`() {
        val id = "UC" + "z".repeat(22)
        assertEquals("https://www.youtube.com/channel/$id", AutoDownloadPlanner.urlForKey(id))
        assertEquals("https://www.youtube.com/@name", AutoDownloadPlanner.urlForKey("/@name"))
    }

    private fun item(id: String, watched: Int, channel: String?, status: DownloadStatus = DownloadStatus.COMPLETED) =
        DownloadItem(
            id = id, videoId = id, title = id, thumbnailUrl = null, filePath = null, progress = 100,
            status = status, isAudioOnly = false, watchedPercent = watched, uploaderUrl = channel
        )

    @Test fun `a channel can switch auto-delete on or off whatever the global switch says`() {
        val items = listOf(item("a", 100, "chA"), item("b", 100, "chB"), item("c", 100, "chC"), item("d", 50, "chA"), item("e", 100, "chA", DownloadStatus.RUNNING))
        val rule = { url: String? -> when (url) { "chA" -> true; "chB" -> false; else -> null } }
        // global off: only chA (explicitly on) is deleted
        assertEquals(listOf("a"), AutoDelete.targets(items, null, globalOn = false, channelOverride = rule).map { it.id })
        // global on: chA and chC (follows global), but not chB (explicitly off)
        assertEquals(listOf("a", "c"), AutoDelete.targets(items, null, globalOn = true, channelOverride = rule).map { it.id })
        // the item playing now is never touched
        assertEquals(listOf("c"), AutoDelete.targets(items, "a", globalOn = true, channelOverride = rule).map { it.id })
    }
}
