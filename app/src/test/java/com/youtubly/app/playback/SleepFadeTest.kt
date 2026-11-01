package com.youtubly.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepFadeTest {
    @Test fun fullVolumeBeforeFade() {
        assertEquals(1f, SleepFade.volumeFactor(SleepFade.FADE_MS), 0f)
        assertEquals(1f, SleepFade.volumeFactor(10 * 60_000L), 0f)
    }

    @Test fun silentAtTheEnd() {
        assertEquals(0f, SleepFade.volumeFactor(0), 0f)
        assertEquals(0f, SleepFade.volumeFactor(-500), 0f)
    }

    @Test fun decreasesSteadily() {
        var prev = 1.01f
        for (ms in SleepFade.FADE_MS downTo 0 step 1_000) {
            val v = SleepFade.volumeFactor(ms)
            assertTrue("volume must not rise as time runs out", v <= prev)
            prev = v
        }
    }
}
