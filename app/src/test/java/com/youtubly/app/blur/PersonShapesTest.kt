package com.youtubly.app.blur

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonShapesTest {
    /** A 100 x 100 mask with the given filled rectangles (left, top, right, bottom). */
    private fun mask(vararg rects: IntArray): PersonMask {
        val v = FloatArray(100 * 100)
        for (r in rects) for (y in r[1] until r[3]) for (x in r[0] until r[2]) v[y * 100 + x] = 1f
        return PersonMask(v, 100, 100)
    }

    @Test fun `two separate people are two blobs, a speck is ignored`() {
        val blobs = PersonShapes.blobs(mask(intArrayOf(10, 10, 30, 80), intArrayOf(60, 20, 85, 90), intArrayOf(50, 5, 52, 7)))
        assertEquals(2, blobs.size)
        assertArrayEquals(intArrayOf(10, 10, 30, 80), blobs[0])
        assertArrayEquals(intArrayOf(60, 20, 85, 90), blobs[1])
    }

    @Test fun `touching shapes are one blob`() {
        assertEquals(1, PersonShapes.blobs(mask(intArrayOf(10, 10, 40, 80), intArrayOf(40, 30, 70, 80))).size)
    }

    @Test fun `an empty or inconsistent mask gives nothing`() {
        assertTrue(PersonShapes.blobs(PersonMask(FloatArray(0), 0, 0)).isEmpty())
        assertTrue(PersonShapes.blobs(PersonMask(FloatArray(10), 100, 100)).isEmpty())
        assertTrue(PersonShapes.blobs(mask()).isEmpty())
    }

    @Test fun `a blob with a face inside is left to the face rules, one without is a person seen from behind`() {
        val blobs = listOf(intArrayOf(10, 10, 40, 90), intArrayOf(60, 10, 90, 90))
        val faces = listOf(intArrayOf(18, 12, 32, 28))          // inside the first blob only
        val behind = PersonShapes.withoutFace(blobs, faces)
        assertEquals(1, behind.size)
        assertArrayEquals(intArrayOf(60, 10, 90, 90), behind[0])
        assertEquals(2, PersonShapes.withoutFace(blobs, emptyList()).size)
    }

    @Test fun `full body takes the whole blob, upper body stops near the waist`() {
        val blob = intArrayOf(20, 10, 60, 190)                  // 40 wide, 180 tall
        assertArrayEquals(intArrayOf(20, 10, 60, 190), PersonShapes.regionFor(blob, BodyCoverage.FULL_BODY, 100, 200))
        assertArrayEquals(intArrayOf(20, 10, 60, 66), PersonShapes.regionFor(blob, BodyCoverage.UPPER_BODY, 100, 200))   // 10 + 1.4 * 40
        // an upper-body-only blob (already short) is not cut
        assertArrayEquals(intArrayOf(20, 10, 60, 40), PersonShapes.regionFor(intArrayOf(20, 10, 60, 40), BodyCoverage.UPPER_BODY, 100, 200))
    }

    @Test fun `margin grows the region and it stays inside the picture`() {
        val r = PersonShapes.regionFor(intArrayOf(0, 0, 50, 100), BodyCoverage.FULL_BODY, 60, 105, growPct = 10)
        assertEquals(0, r[0]); assertEquals(0, r[1])
        assertEquals(55, r[2]); assertEquals(105, r[3])
    }
}
