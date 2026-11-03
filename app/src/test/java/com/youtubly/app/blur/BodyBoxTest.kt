package com.youtubly.app.blur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyBoxTest {
    // A 100 x 100 face, top-left at (400, 100), in a 1000 x 2000 image.
    private fun box(c: BodyCoverage, grow: Int = 0) = BlurMath.bodyBox(400, 100, 500, 200, 1000, 2000, c, grow)

    @Test fun `face only stays the size of the face plus hair room`() {
        val b = box(BodyCoverage.FACE)
        assertEquals(400, b[0]); assertEquals(500, b[2])
        assertEquals(50, b[1]); assertEquals(200, b[3])
    }

    @Test fun `upper body reaches the waist and about three face widths across`() {
        val b = box(BodyCoverage.UPPER_BODY)
        assertEquals(280, b[0]); assertEquals(620, b[2])        // 450 +- 1.7 faces
        assertEquals(520, b[3])                                  // 3.2 faces below the chin
    }

    @Test fun `full body reaches the feet`() {
        val b = box(BodyCoverage.FULL_BODY)
        assertEquals(260, b[0]); assertEquals(640, b[2])
        assertEquals(1000, b[3])                                 // 8 faces below the chin
        assertTrue(b[3] - b[1] > 8 * 100)
    }

    @Test fun `the box is clipped to the picture`() {
        // A big face near the bottom: the body would run far past the frame.
        val b = BlurMath.bodyBox(300, 900, 700, 1300, 1000, 1500, BodyCoverage.FULL_BODY)
        assertEquals(1500, b[3])
        assertTrue(b[0] >= 0 && b[2] <= 1000 && b[1] >= 0)
        // A face at the very left edge keeps left >= 0.
        assertEquals(0, BlurMath.bodyBox(0, 100, 100, 200, 1000, 2000, BodyCoverage.FULL_BODY)[0])
    }

    @Test fun `extra margin grows the box`() {
        val plain = box(BodyCoverage.UPPER_BODY)
        val grown = box(BodyCoverage.UPPER_BODY, grow = 10)
        assertTrue(grown[0] < plain[0] && grown[2] > plain[2] && grown[3] > plain[3] && grown[1] <= plain[1])
    }
}
