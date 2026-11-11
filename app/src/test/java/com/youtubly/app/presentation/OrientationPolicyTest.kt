package com.youtubly.app.presentation

import android.content.pm.ActivityInfo
import com.youtubly.app.presentation.components.OrientationPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationPolicyTest {
    private val portrait = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    private val landscape = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    private val free = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    @Test fun `a phone rests in portrait whatever the rotate lock says`() {
        assertEquals(portrait, OrientationPolicy.desired(fullscreen = false, lockLandscape = true, allowRotation = false, base = portrait))
    }

    @Test fun `the fullscreen button turns it landscape and leaving goes back to portrait`() {
        assertEquals(landscape, OrientationPolicy.desired(true, true, false, portrait))
        assertEquals(portrait, OrientationPolicy.desired(false, true, false, portrait))
    }

    @Test fun `rotate to fullscreen lets the phone's rotation decide, also inside fullscreen it started`() {
        assertEquals(free, OrientationPolicy.desired(false, true, true, portrait))
        assertEquals(free, OrientationPolicy.desired(true, false, true, portrait))      // fullscreen reached by rotating: not locked
    }

    @Test fun `a fullscreen from the button still wins over free rotation`() {
        assertEquals(landscape, OrientationPolicy.desired(true, true, true, portrait))
    }

    @Test fun `tablets and big screens rotate freely, phones do not`() {
        assertEquals(portrait, OrientationPolicy.baseFor(411))
        assertEquals(portrait, OrientationPolicy.baseFor(599))
        assertEquals(free, OrientationPolicy.baseFor(600))
        assertEquals(free, OrientationPolicy.baseFor(840))
    }
}
