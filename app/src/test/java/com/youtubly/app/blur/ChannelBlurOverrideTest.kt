package com.youtubly.app.blur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelBlurOverrideTest {
    private val general = BlurSettings(enabled = false, blurFemale = true, blurMale = false, bodyCoverage = BodyCoverage.FULL_BODY)

    @Test fun `a channel without its own choices gets the general settings`() {
        assertSame(general, general.forChannel("UCnone"))
        assertSame(general, general.forChannel(null))
    }

    @Test fun `coverage and gender are replaced only for that channel`() {
        val s = general.copy(channelOverrides = mapOf("UC1" to ChannelBlurOverride(coverage = BodyCoverage.FACE, gender = GenderRule.BOTH)))
        val one = s.forChannel("UC1")
        assertEquals(BodyCoverage.FACE, one.bodyCoverage)
        assertTrue(one.blurFemale && one.blurMale)
        assertEquals(BodyCoverage.FULL_BODY, s.forChannel("UC2").bodyCoverage)
        assertFalse(s.forChannel("UC2").blurMale)
    }

    @Test fun `gender rules map to the two switches`() {
        fun rule(g: GenderRule) = general.copy(channelOverrides = mapOf("c" to ChannelBlurOverride(gender = g))).forChannel("c")
        assertTrue(rule(GenderRule.FEMALE).blurFemale && !rule(GenderRule.FEMALE).blurMale)
        assertTrue(!rule(GenderRule.MALE).blurFemale && rule(GenderRule.MALE).blurMale)
    }

    @Test fun `force switches blur on for one channel while it is off in general`() {
        val s = general.copy(channelOverrides = mapOf("UC1" to ChannelBlurOverride(force = true)))
        assertTrue(s.forChannel("UC1").enabled)
        assertFalse(s.forChannel("UC2").enabled)
    }

    @Test fun `overrides survive a round trip, empty ones are ignored, bad data is too`() {
        val map = mapOf("UC1" to ChannelBlurOverride(true, BodyCoverage.UPPER_BODY, GenderRule.MALE))
        assertEquals(map, ChannelBlurOverride.decodeMap(ChannelBlurOverride.encodeMap(map)))
        assertTrue(ChannelBlurOverride.decodeMap("nonsense").isEmpty())
        assertTrue(ChannelBlurOverride().isEmpty)
    }
}
