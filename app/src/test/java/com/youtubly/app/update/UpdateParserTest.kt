package com.youtubly.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateParserTest {
    private fun release(body: String = "- Fixed things", extraAsset: String = "", draft: Boolean = false) = """
        {
          "tag_name": "build-101", "draft": $draft, "published_at": "2026-11-01T10:00:00Z",
          "body": ${kotlinx.serialization.json.JsonPrimitive(body)},
          "assets": [
            {"name": "Youtubly-1_1_0-build101.apk", "url": "https://api.github.com/repos/o/r/releases/assets/1",
             "browser_download_url": "https://github.com/o/r/releases/download/build-101/Youtubly-1_1_0-build101.apk", "size": 52428800},
            {"name": "Youtubly-1_1_0-build101.apk.sha256", "url": "https://api.github.com/repos/o/r/releases/assets/2",
             "browser_download_url": "https://github.com/o/r/releases/download/build-101/Youtubly-1_1_0-build101.apk.sha256", "size": 100}
            $extraAsset
          ]
        }
    """.trimIndent()

    @Test fun `reads version, build, size and links from the APK asset name`() {
        val r = UpdateParser.parseRelease(release())!!
        assertEquals("1.1.0", r.versionName)
        assertEquals(101, r.build)
        assertEquals(52428800L, r.sizeBytes)
        assertTrue(r.apkUrl.endsWith("Youtubly-1_1_0-build101.apk"))
        assertEquals("https://api.github.com/repos/o/r/releases/assets/1", r.apkApiUrl)
        assertTrue(r.sha256Url!!.endsWith(".sha256"))
        assertEquals("- Fixed things", r.notes)
        assertFalse(r.required)
    }

    @Test fun `a multi-part version and a longer build number parse`() {
        val json = """{"assets":[{"name":"Youtubly-2_10_3-build1234.apk","browser_download_url":"https://x/y.apk","url":"u","size":1}],"body":""}"""
        val r = UpdateParser.parseRelease(json)!!
        assertEquals("2.10.3", r.versionName)
        assertEquals(1234, r.build)
    }

    @Test fun `required marker is detected and removed from the notes`() {
        val r = UpdateParser.parseRelease(release(body = "- Security fix\n\n[required]"))!!
        assertTrue(r.required)
        assertEquals("- Security fix", r.notes)
    }

    @Test fun `releases without a matching APK, drafts and junk are ignored`() {
        assertNull(UpdateParser.parseRelease("""{"assets":[{"name":"notes.txt","browser_download_url":"https://x"}]}"""))
        assertNull(UpdateParser.parseRelease("""{"assets":[{"name":"Youtubly.apk","browser_download_url":"https://x"}]}"""))
        assertNull(UpdateParser.parseRelease(release(draft = true)))
        assertNull(UpdateParser.parseRelease("not json"))
        assertNull(UpdateParser.parseRelease("""{"message":"Not Found"}"""))
    }

    @Test fun `no checksum asset means no checksum link`() {
        val json = """{"assets":[{"name":"Youtubly-1_0_0-build5.apk","browser_download_url":"https://x/a.apk","url":"u","size":9}]}"""
        assertNull(UpdateParser.parseRelease(json)!!.sha256Url)
    }

    @Test fun `newer means a strictly higher build number`() {
        assertTrue(UpdateParser.isNewer(102, 101))
        assertFalse(UpdateParser.isNewer(101, 101))
        assertFalse(UpdateParser.isNewer(100, 101))
    }

    @Test fun `checksum file formats`() {
        val h = "a".repeat(64)
        assertEquals(h, UpdateParser.parseSha256("$h  Youtubly-1_1_0-build101.apk\n"))
        assertEquals(h, UpdateParser.parseSha256(h.uppercase()))
        assertNull(UpdateParser.parseSha256("nothing here"))
    }

    @Test fun `sizes are readable`() {
        assertEquals("50.0 MB", UpdateParser.humanSize(52428800))
        assertEquals("512 KB", UpdateParser.humanSize(524288))
        assertEquals("", UpdateParser.humanSize(-1))
        assertNotNull(UpdateParser.humanSize(1))
    }
}
