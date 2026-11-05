package com.youtubly.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryTest {
    @Test fun `listen only at or below the limit while not charging`() {
        assertTrue(LowBattery.listenOnly(levelPct = 15, charging = false, thresholdPct = 20))
        assertTrue(LowBattery.listenOnly(20, false, 20))
        assertFalse(LowBattery.listenOnly(21, false, 20))
    }

    @Test fun `never while charging, never when switched off, never when the level is unknown`() {
        assertFalse(LowBattery.listenOnly(5, true, 20))
        assertFalse(LowBattery.listenOnly(5, false, 0))
        assertFalse(LowBattery.listenOnly(-1, false, 20))
    }

    @Test fun `percent from raw values`() {
        assertEquals(50, LowBattery.percent(50, 100))
        assertEquals(25, LowBattery.percent(1, 4))
        assertEquals(-1, LowBattery.percent(-1, 100))
        assertEquals(-1, LowBattery.percent(10, 0))
    }
}
