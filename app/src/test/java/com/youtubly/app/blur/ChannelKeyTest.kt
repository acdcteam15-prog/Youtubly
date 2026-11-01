package com.youtubly.app.blur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelKeyTest {
    private val id = "UCBR8-60-B28hp2BmDPdntcQ"

    @Test fun sameKeyForEveryUrlShape() {
        assertEquals(id, channelKeyOf("https://www.youtube.com/channel/$id"))
        assertEquals(id, channelKeyOf("https://m.youtube.com/channel/$id/videos?view=0"))
        assertEquals(id, channelKeyOf(id))
    }

    @Test fun handlesFallBackToPath() {
        assertEquals("/@somecreator", channelKeyOf("https://www.youtube.com/@SomeCreator/"))
    }

    @Test fun blankIsNull() {
        assertNull(channelKeyOf(null)); assertNull(channelKeyOf("  "))
    }

    @Test fun refRoundTrips() {
        val r = ChannelRef(id, "Name", null)
        assertEquals(r, ChannelRef.decode(r.encode()))
    }
}
