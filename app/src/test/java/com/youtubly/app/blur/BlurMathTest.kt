package com.youtubly.app.blur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurMathTest {
    @Test fun fixedModeIgnoresFaceSize() {
        assertEquals(BlurMath.gaussianFactor(30, 40, false), BlurMath.gaussianFactor(30, 800, false))
        assertEquals(15, BlurMath.gaussianFactor(30, 100, false))
    }

    @Test fun scaledModeGrowsWithFaceSize() {
        val small = BlurMath.gaussianFactor(30, 60, true)
        val big = BlurMath.gaussianFactor(30, 600, true)
        assertTrue("big faces need stronger blur ($small vs $big)", big > small)
        // A face filling the frame is blurred harder than the old fixed setting.
        assertTrue(big > BlurMath.gaussianFactor(30, 600, false))
    }

    @Test fun scaledModeStaysInBounds() {
        assertEquals(2, BlurMath.gaussianFactor(5, 1, true))
        assertEquals(60, BlurMath.gaussianFactor(50, 10_000, true))
    }
}
