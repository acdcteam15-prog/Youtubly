package com.youtubly.app.domain

import com.youtubly.app.core.SearchText
import com.youtubly.app.domain.channel.PlaylistAutoFinish
import com.youtubly.app.domain.channel.PlaylistProgress
import com.youtubly.app.domain.channel.VideoSort
import com.youtubly.app.domain.model.DownloadOption
import com.youtubly.app.domain.model.DownloadOptions
import com.youtubly.app.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFeaturesTest {

    // ---------- search text ----------
    @Test fun `arabic search ignores diacritics and letter variants`() {
        assertTrue(SearchText.matches("النِّهَايَةُ في الفِتَنِ والمَلَاحِم", "الفتن"))
        assertTrue(SearchText.matches("أحمد بن حنبل", "احمد"))
        assertTrue(SearchText.matches("المدرسة", "المدرسه"))
        assertTrue(SearchText.matches("على الله", "علي"))
    }

    @Test fun `search needs every word, ignores case, and a blank query matches everything`() {
        assertTrue(SearchText.matches("Kitab at-Tawhid part 5", "tawhid KITAB"))
        assertFalse(SearchText.matches("Kitab at-Tawhid part 5", "tawhid fiqh"))
        assertTrue(SearchText.matches("anything", "  "))
    }

    // ---------- sort ----------
    private fun v(id: String, views: Long) = Video(id, "u/$id", id, "ch", null, null, 60, views, null)

    @Test fun `popular sorts by views with unknown counts last, oldest reverses the loaded order`() {
        val list = listOf(v("new", 10), v("mid", 500), v("old", 50), v("unknown", -1))
        assertEquals(listOf("mid", "old", "new", "unknown"), VideoSort.POPULAR.apply(list).map { it.id })
        assertEquals(listOf("unknown", "old", "mid", "new"), VideoSort.OLDEST.apply(list).map { it.id })
        assertEquals(list, VideoSort.LATEST.apply(list))
    }

    // ---------- playlist progress ----------
    private val ids = listOf("a", "b", "c", "d", "e")

    @Test fun `watched count uses the 90 percent threshold`() {
        val p = mapOf("a" to 1f, "b" to 0.91f, "c" to 0.5f)
        assertEquals(2, PlaylistProgress.watchedCount(ids, p))
        assertEquals(0, PlaylistProgress.watchedCount(ids, emptyMap()))
    }

    @Test fun `continue points at the unfinished video, or the one after a finished one`() {
        assertNull(PlaylistProgress.continueIndex(ids, emptyMap()))
        // c was started but not finished → continue c
        assertEquals(2, PlaylistProgress.continueIndex(ids, mapOf("a" to 1f, "b" to 1f, "c" to 0.4f)))
        // c finished → continue d
        assertEquals(3, PlaylistProgress.continueIndex(ids, mapOf("a" to 1f, "c" to 0.95f)))
        // the last one finished → nothing left to continue
        assertNull(PlaylistProgress.continueIndex(ids, mapOf("e" to 1f)))
        // the last started video wins even if earlier ones are unfinished
        assertEquals(4, PlaylistProgress.continueIndex(ids, mapOf("a" to 0.3f, "e" to 0.2f)))
    }

    // ---------- bulk download quality ----------
    private fun video(h: Int) = DownloadOption("${h}p", "v$h", false, "v|${h}p|MPEG_4", "mp4")
    private fun audio(kbps: Int) = DownloadOption("$kbps kbps", "a$kbps", true, "a|$kbps|M4A", "m4a")

    @Test fun `picks the best video up to the chosen height`() {
        val opts = listOf(video(1080), video(720), video(360), audio(128), audio(48))
        assertEquals("v720", DownloadOptions.pickFor(opts, 720)?.url)
        assertEquals("v1080", DownloadOptions.pickFor(opts, 1080)?.url)
        assertEquals("v360", DownloadOptions.pickFor(opts, 480)?.url)
    }

    @Test fun `falls back to the smallest video when everything is taller, and picks the best audio for audio only`() {
        val opts = listOf(video(1080), video(720), audio(48), audio(128))
        assertEquals("v720", DownloadOptions.pickFor(opts, 360)?.url)
        assertEquals("a128", DownloadOptions.pickFor(opts, null)?.url)
        assertNull(DownloadOptions.pickFor(listOf(video(720)), null))
    }

    // ---------- auto-remove finished ----------
    @Test fun `only watched videos of each auto-remove playlist are due`() {
        val candidates = listOf(1L to "a", 1L to "b", 1L to "c", 2L to "b", 2L to "z")
        val due = PlaylistAutoFinish.due(candidates, watched = setOf("a", "b", "x"))
        assertEquals(mapOf(1L to setOf("a", "b"), 2L to setOf("b")), due)
    }

    @Test fun `nothing is due when nothing was watched or there are no candidates`() {
        assertTrue(PlaylistAutoFinish.due(listOf(1L to "a"), emptySet()).isEmpty())
        assertTrue(PlaylistAutoFinish.due(emptyList(), setOf("a")).isEmpty())
    }
}
