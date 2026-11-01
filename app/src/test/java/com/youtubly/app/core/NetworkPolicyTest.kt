package com.youtubly.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkPolicyTest {

    @Test
    fun `auto without data saver means no cap`() {
        assertNull(NetworkPolicy.capFor(0, dataSaver = false))
    }

    @Test
    fun `chosen quality is the cap without data saver`() {
        assertEquals(720, NetworkPolicy.capFor(720, dataSaver = false))
    }

    @Test
    fun `data saver caps at 360p even when a higher quality is chosen`() {
        assertEquals(360, NetworkPolicy.capFor(0, dataSaver = true))
        assertEquals(360, NetworkPolicy.capFor(1080, dataSaver = true))
    }

    @Test
    fun `smallThumbnail rewrites YouTube thumbnails to mqdefault`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc/mqdefault.jpg",
            NetworkPolicy.smallThumbnail("https://i.ytimg.com/vi/abc/hq720.jpg?sqp=xyz&rs=AOn")
        )
        assertEquals(
            "https://i.ytimg.com/vi_webp/abc/mqdefault.webp",
            NetworkPolicy.smallThumbnail("https://i.ytimg.com/vi_webp/abc/maxresdefault.webp")
        )
        assertEquals(
            "https://i.ytimg.com/vi/abc/mqdefault_live.jpg",
            NetworkPolicy.smallThumbnail("https://i.ytimg.com/vi/abc/hqdefault_live.jpg")
        )
    }

    @Test
    fun `smallThumbnail leaves other URLs alone`() {
        assertEquals("https://example.com/a.jpg", NetworkPolicy.smallThumbnail("https://example.com/a.jpg"))
        assertNull(NetworkPolicy.smallThumbnail(null))
    }
}
