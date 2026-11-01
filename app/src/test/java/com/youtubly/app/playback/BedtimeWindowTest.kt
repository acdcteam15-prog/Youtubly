package com.youtubly.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BedtimeWindowTest {
    private fun m(h: Int, min: Int = 0) = h * 60 + min

    @Test fun crossesMidnight() {
        assertTrue(Bedtime.inWindow(m(23), m(22), m(7)))
        assertTrue(Bedtime.inWindow(m(3), m(22), m(7)))
        assertTrue(Bedtime.inWindow(m(22), m(22), m(7)))   // start inclusive
        assertFalse(Bedtime.inWindow(m(7), m(22), m(7)))   // end exclusive
        assertFalse(Bedtime.inWindow(m(12), m(22), m(7)))
    }

    @Test fun sameDayWindow() {
        assertTrue(Bedtime.inWindow(m(14), m(13), m(15)))
        assertFalse(Bedtime.inWindow(m(15), m(13), m(15)))
        assertFalse(Bedtime.inWindow(m(9), m(13), m(15)))
    }

    @Test fun emptyWindowNeverActive() {
        assertFalse(Bedtime.inWindow(m(22), m(22), m(22)))
    }
}
