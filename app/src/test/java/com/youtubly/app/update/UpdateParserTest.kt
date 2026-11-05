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

    @Test fun `the patch made for the installed build is found, others are ignored`() {
        val json = release(extraAsset = """,
            {"name": "Youtubly-patch-from99-to101.ytpatch", "url": "https://api.github.com/repos/o/r/releases/assets/7",
             "browser_download_url": "https://github.com/o/r/releases/download/build-101/Youtubly-patch-from99-to101.ytpatch", "size": 4194304},
            {"name": "Youtubly-patch-from98-to101.ytpatch", "url": "u8", "browser_download_url": "https://x/p98", "size": 9000000}""")
        val mine = UpdateParser.parseRelease(json, currentBuild = 99)!!
        assertTrue(mine.patchUrl!!.endsWith("Youtubly-patch-from99-to101.ytpatch"))
        assertEquals("https://api.github.com/repos/o/r/releases/assets/7", mine.patchApiUrl)
        assertEquals(4194304L, mine.patchSize)
        // a phone on a build with no patch, or not telling its build, gets none
        assertNull(UpdateParser.parseRelease(json, currentBuild = 50)!!.patchUrl)
        assertNull(UpdateParser.parseRelease(json)!!.patchUrl)
        // a patch for a different target build is not used
        assertNull(UpdateParser.parseRelease(json.replace("to101", "to999"), currentBuild = 99)!!.patchUrl)
    }
}
