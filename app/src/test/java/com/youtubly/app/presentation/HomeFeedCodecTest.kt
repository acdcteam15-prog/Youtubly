package com.youtubly.app.presentation

import com.youtubly.app.domain.model.Video
import com.youtubly.app.presentation.home.HomeFeedCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedCodecTest {
    private val v = Video("abc", "https://y/abc", "عنوان \"q\"", "Ch", null, "https://t", 61, 1000, "1 day ago", true, null, "tok")

    @Test fun roundTrip() {
        val back = HomeFeedCodec.decode(HomeFeedCodec.encode(listOf(v)))
        assertEquals(listOf(v.copy(notInterestedToken = null)), back)
    }

    @Test fun garbageIsEmpty() {
        assertTrue(HomeFeedCodec.decode("not json").isEmpty())
        assertTrue(HomeFeedCodec.decode("[{\"title\":\"no id\"}]").isEmpty())
    }

    @Test fun capped() {
        val many = (1..100).map { v.copy(id = "id$it") }
        assertEquals(HomeFeedCodec.MAX, HomeFeedCodec.decode(HomeFeedCodec.encode(many)).size)
    }
}
