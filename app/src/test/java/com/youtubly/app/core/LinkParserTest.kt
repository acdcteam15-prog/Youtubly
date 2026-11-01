package com.youtubly.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkParserTest {
    private val id = "dQw4w9WgXcQ"
    private val watch = "https://www.youtube.com/watch?v=$id"

    @Test fun `watch links on www and m hosts`() {
        assertEquals(DeepLink.Video(watch), LinkParser.parse("https://www.youtube.com/watch?v=$id&list=PL123"))
        assertEquals(DeepLink.Video(watch), LinkParser.parse("https://m.youtube.com/watch?feature=share&v=$id"))
        assertEquals(DeepLink.Video(watch), LinkParser.parse("http://youtube.com/watch?v=$id"))
    }

    @Test fun `short links, shorts, live and embed open the normal player`() {
        assertEquals(DeepLink.Video(watch), LinkParser.parse("https://youtu.be/$id?si=abc"))
        assertEquals(DeepLink.Video(watch), LinkParser.parse("https://www.youtube.com/shorts/$id"))
        assertEquals(DeepLink.Video(watch), LinkParser.parse("https://www.youtube.com/live/$id?feature=share"))
        assertEquals(DeepLink.Video(watch), LinkParser.parse("https://www.youtube.com/embed/$id"))
    }

    @Test fun `timestamps in every common form`() {
        assertEquals(DeepLink.Video(watch, 90_000), LinkParser.parse("https://youtu.be/$id?t=90"))
        assertEquals(DeepLink.Video(watch, 90_000), LinkParser.parse("https://www.youtube.com/watch?v=$id&t=1m30s"))
        assertEquals(DeepLink.Video(watch, 3_723_000), LinkParser.parse("https://www.youtube.com/watch?v=$id&t=1h2m3s"))
        assertEquals(DeepLink.Video(watch, 45_000), LinkParser.parse("https://www.youtube.com/watch?v=$id#t=45s"))
        assertEquals(DeepLink.Video(watch), LinkParser.parse("https://youtu.be/$id?t=0"))
    }

    @Test fun `channel links`() {
        assertEquals(DeepLink.Channel("https://www.youtube.com/channel/UCabc123"), LinkParser.parse("https://www.youtube.com/channel/UCabc123/videos"))
        assertEquals(DeepLink.Channel("https://www.youtube.com/@SomeHandle"), LinkParser.parse("https://m.youtube.com/@SomeHandle"))
        assertEquals(DeepLink.Channel("https://www.youtube.com/c/Name"), LinkParser.parse("https://www.youtube.com/c/Name"))
    }

    @Test fun `playlist links open the playlist, but a video inside a playlist still opens the video`() {
        val pl = "PLabcdefghij123"
        val expected = DeepLink.Playlist("https://www.youtube.com/playlist?list=$pl")
        assertEquals(expected, LinkParser.parse("https://www.youtube.com/playlist?list=$pl"))
        assertEquals(expected, LinkParser.parse("https://m.youtube.com/playlist?list=$pl&si=xyz"))
        assertEquals(expected, LinkParser.parseText("Series: https://www.youtube.com/playlist?list=$pl."))
        assertEquals(DeepLink.Playlist("https://www.youtube.com/playlist?list=UUabcdefghij123"), LinkParser.parse("https://www.youtube.com/playlist?list=UUabcdefghij123"))
        assertEquals(DeepLink.Video(watch), LinkParser.parse("https://www.youtube.com/watch?v=$id&list=$pl"))
        // Private lists and auto-generated mixes can't be opened.
        assertNull(LinkParser.parse("https://www.youtube.com/playlist?list=WL"))
        assertNull(LinkParser.parse("https://www.youtube.com/playlist?list=RDdQw4w9WgXcQ"))
        assertNull(LinkParser.parse("https://www.youtube.com/playlist"))
    }

    @Test fun `shared text from chat apps`() {
        assertEquals(DeepLink.Video(watch, 10_000), LinkParser.parseText("Watch this!! https://youtu.be/$id?t=10."))
        assertEquals(DeepLink.Video(watch), LinkParser.parseText("youtube.com/watch?v=$id"))
        assertNull(LinkParser.parseText("no link here"))
    }

    @Test fun `rejects non-YouTube and malformed links`() {
        assertNull(LinkParser.parse("https://example.com/watch?v=$id"))
        assertNull(LinkParser.parse("https://www.youtube.com/watch?v=short"))
        assertNull(LinkParser.parse("https://www.youtube.com/feed/subscriptions"))
    }

    @Test fun `time formats`() {
        assertEquals(90_000L, LinkParser.parseTime("1:30"))
        assertEquals(3_723_000L, LinkParser.parseTime("1:02:03"))
        assertNull(LinkParser.parseTime("abc"))
        assertNull(LinkParser.parseTime(""))
    }
}
