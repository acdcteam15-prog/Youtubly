package com.youtubly.app.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSpecTest {
    private fun r(a: Long, b: Long) = TimeRange(a, b)

    private val spec = ExportSpec(
        clips = listOf(
            ClipSpec("/storage/emulated/0/Android/data/x/Youtubly/a|b,c=d 1-2%.mp4", 60_000, listOf(r(0, 1_000), r(2_000, 5_000)), 1280, 720),
            ClipSpec("/x/ü ñ.mp4", 10_000, listOf(r(0, 10_000)))
        ),
        output = OutputSettings(720, 2_500)
    )

    @Test
    fun `codec round trip, including awkward paths`() {
        assertEquals(spec, ExportSpecCodec.decode(ExportSpecCodec.encode(spec)))
        val plain = ExportSpec(listOf(ClipSpec("/a.mp4", 5_000, listOf(r(100, 4_000)))))
        assertEquals(plain, ExportSpecCodec.decode(ExportSpecCodec.encode(plain)))
    }

    @Test
    fun `codec rejects garbage instead of throwing`() {
        assertNull(ExportSpecCodec.decode(null))
        assertNull(ExportSpecCodec.decode(""))
        assertNull(ExportSpecCodec.decode("v=2\nclip=a|1|0|0|0-1"))
        assertNull(ExportSpecCodec.decode("v=1\nclip=a|x|0|0|0-1"))
        assertNull(ExportSpecCodec.decode("v=1\nclip=a|1|0|0|5-1"))
        assertNull(ExportSpecCodec.decode("v=1\nclip=a|1|0"))
        assertNull(ExportSpecCodec.decode("v=1\nout=1,2"))
        assertNull(ExportSpecCodec.decode("v=1\nout=1\nclip=a|1|0|0|0-1"))
    }

    @Test
    fun `fast path only for a plain single-range trim`() {
        assertTrue(ExportSpec(listOf(ClipSpec("/a", 9_000, listOf(r(0, 5_000))))).isSimpleTrim)
        assertFalse(ExportSpec(listOf(ClipSpec("/a", 9_000, listOf(r(0, 5_000)))), OutputSettings(maxHeight = 480)).isSimpleTrim)
        assertFalse(spec.isSimpleTrim)
    }

    @Test
    fun `totals, validity and summary`() {
        assertEquals(14_000, spec.totalMs)
        assertTrue(spec.isValid)
        assertFalse(ExportSpec(emptyList()).isValid)
        assertFalse(ExportSpec(listOf(ClipSpec("/a", 9_000, listOf(r(0, 400))))).isValid)
        assertEquals("Merge 2 videos · 0:14 · 720p · 2.5 Mbps", spec.summary())
        assertEquals("Cut 1 part out · 0:04", ExportSpec(listOf(spec.clips[0])).summary())
        assertEquals("Trim 0:01–0:05 · 0:04", ExportSpec(listOf(ClipSpec("/a", 9_000, listOf(r(1_000, 5_000))))).summary())
    }

    @Test
    fun `target size is even and never upscales`() {
        assertEquals(1280 to 720, OutputMath.targetSize(1280, 720, null))
        assertEquals(852 to 480, OutputMath.targetSize(1280, 720, 480))
        assertEquals(640 to 360, OutputMath.targetSize(640, 360, 1080))
        assertEquals(360 to 640, OutputMath.targetSize(720, 1280, 640)) // vertical video
        assertEquals(852 to 480, OutputMath.targetSize(855, 481, 480)) // 853.2 → 853 → even 852
        assertNull(OutputMath.targetSize(0, 720, 480))
    }

    @Test
    fun `size estimates`() {
        val one = ExportSpec(listOf(ClipSpec("/a", 60_000, listOf(r(0, 30_000)))))
        assertEquals(55_000_000, OutputMath.estimateBytes(one, listOf(100_000_000)))
        val kbps = one.copy(output = OutputSettings(videoBitrateKbps = 1_000))
        assertEquals((1_128L * 1000 / 8) * 30, OutputMath.estimateBytes(kbps, listOf(100_000_000)))
        assertEquals(0, OutputMath.estimateBytes(one, emptyList()))
    }

    @Test
    fun `format helpers`() {
        assertEquals("2.5 Mbps", EditorFormat.bitrate(2_500))
        assertEquals("2 Mbps", EditorFormat.bitrate(2_000))
        assertEquals("800 kbps", EditorFormat.bitrate(800))
        assertEquals("1.5 MB", EditorFormat.size(1_572_864))
        assertEquals("1 KB", EditorFormat.size(10))
    }
}

class Phase3SpecTest {
    private fun r(a: Long, b: Long) = TimeRange(a, b)

    @Test
    fun `audio-only specs round trip and ignore video settings`() {
        val audio = ExportSpec(listOf(ClipSpec("/a.m4a", 60_000, listOf(r(0, 1_000), r(5_000, 9_000)))), OutputSettings(480, 900), audioOnly = true)
        assertEquals(audio, ExportSpecCodec.decode(ExportSpecCodec.encode(audio)))
        assertEquals("m4a", audio.fileExtension)
        assertEquals("audio/mp4", audio.mimeType)
        assertEquals("Cut 1 part out · 0:05 · audio", audio.summary())
        // Estimate falls back to proportional (bitrate setting ignored for audio).
        assertEquals((10_000_000L * 5_000 / 60_000 * 1.1).toLong(), OutputMath.estimateBytes(audio, listOf(10_000_000)))
        // Single-range audio trim is a "simple" export even if video settings are set.
        assertTrue(audio.copy(clips = listOf(ClipSpec("/a", 9_000, listOf(r(0, 5_000))))).isSimpleTrim)
    }

    @Test
    fun `old specs without the audio line decode as video`() {
        val decoded = ExportSpecCodec.decode("v=1\nout=-,-\nclip=%2Fa.mp4|9000|0|0|0-5000")
        assertEquals(false, decoded!!.audioOnly)
        assertEquals("mp4", decoded.fileExtension)
    }

    @Test
    fun `snapshot and output names`() {
        val now = java.time.LocalDateTime.of(2026, 9, 26, 9, 5, 7)
        assertEquals("My_clip_frame_1m05s_20260926_090507.jpg", EditorFormat.snapshotFileName("My/clip", 65_900, now, SnapshotFormat.JPG))
        assertEquals("video_frame_0m00s_20260926_090507.png", EditorFormat.snapshotFileName("", -1, now, SnapshotFormat.PNG))
        assertEquals("a_edit_20260926_090507.m4a", EditorFormat.outputFileName("a", now, "m4a"))
    }
}

class ConvertRulesTest {
    @Test
    fun `which files need converting`() {
        assertTrue(ConvertRules.needsConversion("/x/My video.webm", audioOnly = false))
        assertTrue(ConvertRules.needsConversion("/x/a.3GP", audioOnly = false))
        assertFalse(ConvertRules.needsConversion("/x/a.MP4", audioOnly = false))
        assertTrue(ConvertRules.needsConversion("/x/song.webm", audioOnly = true))
        assertFalse(ConvertRules.needsConversion("/x/song.m4a", audioOnly = true))
        assertTrue(ConvertRules.needsConversion("/x.dir/noext", audioOnly = false)) // dot in folder name only
        assertEquals("WEBM", ConvertRules.formatLabel("/x/a.webm"))
        assertEquals("this format", ConvertRules.formatLabel("/x.y/noext"))
    }

    @Test
    fun `whole-file conversion spec`() {
        val full = ExportSpec(listOf(ClipSpec("/a.webm", 9_000, listOf(TimeRange(0, 9_000)))))
        assertTrue(full.isWholeFileConversion)
        assertTrue(full.isValid)
        assertEquals("Convert to MP4 · 0:09", full.summary())
        assertEquals("Convert to M4A · 0:09 · audio", full.copy(audioOnly = true).summary())
        assertFalse(full.copy(clips = listOf(ClipSpec("/a.webm", 9_000, listOf(TimeRange(0, 8_000))))).isWholeFileConversion)
        assertFalse(full.copy(output = OutputSettings(maxHeight = 480)).isWholeFileConversion)
    }
}
