package com.youtubly.app.domain

import com.youtubly.app.core.CrashLoop
import com.youtubly.app.domain.library.ChannelGroups
import com.youtubly.app.domain.library.GroupFeed
import com.youtubly.app.domain.library.UploadAge
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.playback.Transcript
import com.youtubly.app.domain.playback.pickSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BigFeaturesTest {
    // ---------- transcript ----------
    private val vtt = """
        WEBVTT
        Kind: captions
        Language: en

        00:00:01.000 --> 00:00:03.000 align:start position:0%
        Hello <c>world</c> &amp; friends

        00:00:03.000 --> 00:00:05.000
        Hello world & friends
        second line

        00:01:02.500 --> 00:01:04.000
        third
    """.trimIndent()

    @Test fun `vtt tags and entities are cleaned and rolling repeats dropped`() {
        val cues = Transcript.parseVtt(vtt)
        assertEquals(3, cues.size)
        assertEquals("Hello world & friends", cues[0].text)
        assertEquals("second line", cues[1].text)          // the repeated first line is not shown twice
        assertEquals(62_500L, cues[2].startMs)
    }

    @Test fun `current line follows the playback position`() {
        val cues = Transcript.parseVtt(vtt)
        assertEquals(-1, Transcript.indexAt(cues, 500))
        assertEquals(0, Transcript.indexAt(cues, 1_000))
        assertEquals(1, Transcript.indexAt(cues, 4_000))
        assertEquals(2, Transcript.indexAt(cues, 99_000))
    }

    @Test fun `transcript search ignores arabic diacritics`() {
        val cues = listOf(
            com.youtubly.app.domain.playback.TranscriptCue(0, "\u0628\u0650\u0633\u0652\u0645\u0650 \u0627\u0644\u0644\u0647"),
            com.youtubly.app.domain.playback.TranscriptCue(1000, "something else")
        )
        assertEquals(listOf(0), Transcript.search(cues, "\u0628\u0633\u0645").map { it.first })
        assertEquals(2, Transcript.search(cues, "").size)
    }

    @Test fun `timestamps read well`() {
        assertEquals("0:05", Transcript.stamp(5_000))
        assertEquals("12:40", Transcript.stamp(760_000))
        assertEquals("1:02:03", Transcript.stamp(3_723_000))
    }

    // ---------- speed ----------
    @Test fun `video speed beats channel speed beats global`() {
        assertEquals(1.5f, pickSpeed(1.5f, 2f, 1f), 0f)
        assertEquals(2f, pickSpeed(null, 2f, 1f), 0f)
        assertEquals(1f, pickSpeed(null, null, 1f), 0f)
    }

    // ---------- channel groups ----------
    @Test fun `groups can be built, renamed and emptied`() {
        var g = emptyMap<String, List<String>>()
        g = ChannelGroups.add(g, "Lectures", "u1")
        g = ChannelGroups.add(g, "Lectures", "u2")
        g = ChannelGroups.add(g, "Lectures", "u1")            // no duplicate
        g = ChannelGroups.add(g, "News", "u1")
        g = ChannelGroups.add(g, "  ", "u3")                  // blank name ignored
        assertEquals(listOf("u1", "u2"), g["Lectures"])
        assertEquals(listOf("Lectures", "News"), ChannelGroups.groupsOf(g, "u1"))
        g = ChannelGroups.rename(g, "Lectures", "Study")
        assertEquals(listOf("Study", "News"), g.keys.toList())
        g = ChannelGroups.remove(g, "Study", "u2")
        assertEquals(listOf("u1"), g["Study"])
        g = ChannelGroups.deleteGroup(g, "News")
        assertFalse("News" in g)
    }

    @Test fun `groups survive a round trip and bad data is ignored`() {
        val g = mapOf("A" to listOf("x", "y"), "B" to emptyList())
        assertEquals(g, ChannelGroups.decode(ChannelGroups.encode(g)))
        assertTrue(ChannelGroups.decode("not json").isEmpty())
        assertTrue(ChannelGroups.decode(null).isEmpty())
    }

    // ---------- group feed ----------
    private fun v(id: String, date: String?) = Video(id, "https://youtu.be/$id", id, "c", null, null, 60, 0, date)

    @Test fun `upload ages parse`() {
        assertEquals(180L, UploadAge.minutes("3 hours ago"))
        assertEquals(2 * 1_440L, UploadAge.minutes("2 days ago"))
        assertEquals(10_080L, UploadAge.minutes("Streamed 1 week ago"))
        assertEquals(Long.MAX_VALUE, UploadAge.minutes("whenever"))
        assertEquals(Long.MAX_VALUE, UploadAge.minutes(null))
    }

    @Test fun `group feed is newest first without duplicates`() {
        val a = listOf(v("a1", "2 days ago"), v("a2", "3 weeks ago"))
        val b = listOf(v("b1", "5 hours ago"), v("a1", "2 days ago"), v("b2", null))
        val merged = GroupFeed.merge(listOf(a, b))
        assertEquals(listOf("b1", "a1", "a2", "b2"), merged.map { it.id })
        assertEquals(2, GroupFeed.merge(listOf(a, b), limit = 2).size)
    }

    // ---------- crash loop ----------
    @Test fun `two crashes in ten minutes is a loop, old ones are not`() {
        val now = 1_000_000_000L
        assertFalse(CrashLoop.isLoop(listOf(now - 1_000), now))
        assertTrue(CrashLoop.isLoop(listOf(now - 1_000, now - 60_000), now))
        assertFalse(CrashLoop.isLoop(listOf(now - 3_600_000, now - 60_000), now))
        assertEquals(listOf(now - 60_000), CrashLoop.prune(listOf(now - 3_600_000, now - 60_000), now))
    }
}
