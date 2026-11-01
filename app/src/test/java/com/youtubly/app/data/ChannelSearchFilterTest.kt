package com.youtubly.app.data

import com.youtubly.app.data.repository.ChannelRepositoryImpl
import com.youtubly.app.domain.model.Channel
import com.youtubly.app.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSearchFilterTest {
    private val channel = Channel("UCabc", "https://www.youtube.com/channel/UCabc", "Mine", "avatar", null, 0, null, emptyList())
    private fun v(id: String, name: String, url: String?) = Video(id, "u$id", id, name, url, null, 0, 0, null)

    @Test
    fun `keeps own and owner-less results, drops other channels, fills owner`() {
        val out = ChannelRepositoryImpl.onlyThisChannel(
            listOf(
                v("own", "Mine", "https://www.youtube.com/channel/UCabc"),
                v("blank", "", null),
                v("other", "Someone", "https://www.youtube.com/channel/UCzzz")
            ),
            channel
        )
        assertEquals(listOf("own", "blank"), out.map { it.id })
        assertEquals("Mine", out[1].uploaderName)
        assertEquals(channel.url, out[1].uploaderUrl)
    }
}
