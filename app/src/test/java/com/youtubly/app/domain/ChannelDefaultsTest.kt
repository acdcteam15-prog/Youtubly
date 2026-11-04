package com.youtubly.app.domain

import com.youtubly.app.domain.playback.ChannelDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelDefaultsTest {
    @Test fun `nothing set means empty`() {
        assertTrue(ChannelDefaults().isEmpty)
        assertFalse(ChannelDefaults(audioOnly = false).isEmpty)
        assertFalse(ChannelDefaults(skipIntroSec = 5).isEmpty)
    }

    @Test fun `intro is skipped from the start`() {
        assertEquals(20_000L, ChannelDefaults.startPosition(null, 0L, 600_000L, 20))
        assertEquals(0L, ChannelDefaults.startPosition(null, 0L, 600_000L, 0))
    }

    @Test fun `a resume point past the intro is kept, one inside it moves to the end of the intro`() {
        assertEquals(300_000L, ChannelDefaults.startPosition(null, 300_000L, 600_000L, 20))
        assertEquals(20_000L, ChannelDefaults.startPosition(null, 12_000L, 600_000L, 20))
    }

    @Test fun `a link time always wins and short videos are not cut`() {
        assertEquals(5_000L, ChannelDefaults.startPosition(5_000L, 5_000L, 600_000L, 20))
        assertEquals(0L, ChannelDefaults.startPosition(null, 0L, 40_000L, 20))      // 40 s video, 20 s intro: leave it
        assertEquals(20_000L, ChannelDefaults.startPosition(null, 0L, 0L, 20))       // unknown length: still skip
    }

    @Test fun `summary reads naturally`() {
        assertEquals("nothing yet", ChannelDefaults.summary(ChannelDefaults()))
        assertEquals(
            "audio only \u00b7 720p \u00b7 captions ar \u00b7 skips first 15 s",
            ChannelDefaults.summary(ChannelDefaults(true, 720, "ar", 15))
        )
        assertEquals("captions off", ChannelDefaults.summary(ChannelDefaults(captionLang = "off")))
    }

    @Test fun `defaults survive a round trip and bad data is ignored`() {
        val map = mapOf("https://youtube.com/channel/UC1" to ChannelDefaults(false, null, "en", 8))
        assertEquals(map, ChannelDefaults.decode(ChannelDefaults.encode(map)))
        assertTrue(ChannelDefaults.decode("garbage").isEmpty())
        assertTrue(ChannelDefaults.decode(null).isEmpty())
    }

    @Test fun `hidden words match titles case and Arabic variants ignored`() {
        assertTrue(ChannelDefaults.hidesTitle(listOf("spoiler"), "Big SPOILER inside"))
        assertFalse(ChannelDefaults.hidesTitle(listOf("spoiler"), "A calm video"))
        assertFalse(ChannelDefaults.hidesTitle(emptyList(), "anything"))
        assertFalse(ChannelDefaults.hidesTitle(listOf("  "), "anything"))
        // alef with hamza and plain alef count as the same letter
        assertTrue(ChannelDefaults.hidesTitle(listOf("\u0623\u062e\u0628\u0627\u0631"), "\u0627\u062e\u0628\u0627\u0631 \u0627\u0644\u064a\u0648\u0645"))
    }

    @Test fun `a channel is found by id or by the url an older version saved`() {
        val id = "UC" + "a".repeat(22)
        val byId = mapOf(id to 1)
        assertEquals(1, ChannelDefaults.lookup(byId, "https://www.youtube.com/channel/$id"))
        assertEquals(1, ChannelDefaults.lookup(byId, "https://www.youtube.com/channel/$id?x=1"))
        val raw = mapOf("https://www.youtube.com/@name" to 2)
        assertEquals(2, ChannelDefaults.lookup(raw, "https://www.youtube.com/@name"))
        assertEquals(null, ChannelDefaults.lookup(byId, null))
        assertEquals(null, ChannelDefaults.lookup(byId, "https://www.youtube.com/channel/UC" + "b".repeat(22)))
    }

    @Test fun `new fields count as set`() {
        assertFalse(ChannelDefaults(volumeBoostDb = 10).isEmpty)
        assertFalse(ChannelDefaults(hiddenKeywords = listOf("x")).isEmpty)
        assertFalse(ChannelDefaults(autoDownload = true).isEmpty)
        assertFalse(ChannelDefaults(autoDeleteWatched = false).isEmpty)
        assertFalse(ChannelDefaults(showShortsTab = false).isEmpty)
        assertTrue(ChannelDefaults(skipOutroSec = 0, hiddenKeywords = emptyList()).isEmpty)
    }
}
