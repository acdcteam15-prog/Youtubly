package com.youtubly.app.system

import com.youtubly.app.data.local.datastore.SponsorCategories
import com.youtubly.app.data.system.SettingsBackupCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupCodecTest {
    @Test fun roundTripKeepsEveryType() {
        val data = mapOf(
            "app" to mapOf<String, Any>("b" to true, "i" to 42, "l" to 9_000_000_000L, "f" to 1.25f,
                "d" to 2.5, "s" to "hello \"quoted\"", "set" to setOf("x", "y")),
            "blur" to mapOf<String, Any>("strength" to 30, "whitelist" to emptySet<String>()),
        )
        val back = SettingsBackupCodec.decode(SettingsBackupCodec.encode(data))
        assertEquals(data, back)
        assertTrue(back["app"]!!["i"] is Int)
        assertTrue(back["app"]!!["l"] is Long)
        assertTrue(back["app"]!!["f"] is Float)
    }

    @Test fun rejectsNonBackups() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupCodec.decode("hello") }
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupCodec.decode("{\"foo\":1}") }
    }

    @Test fun rejectsNewerVersion() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupCodec.decode("{\"version\":99}") }
    }

    @Test fun sponsorDefaultsMatchPreviousBehaviour() {
        // Filler is new and must stay off by default; everything skipped before still is.
        assertTrue("filler" !in SponsorCategories.DEFAULT_SKIP)
        assertEquals(SponsorCategories.ALL.map { it.first }.toSet() - "filler", SponsorCategories.DEFAULT_SKIP)
    }
}
