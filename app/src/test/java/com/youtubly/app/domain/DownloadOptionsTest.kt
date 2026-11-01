package com.youtubly.app.domain

import com.youtubly.app.domain.model.AudioStreamOption
import com.youtubly.app.domain.model.DownloadOptions
import com.youtubly.app.domain.model.StreamInfoResult
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.model.VideoStreamOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOptionsTest {
    private fun info(video: List<VideoStreamOption>, audio: List<AudioStreamOption>) = StreamInfoResult(
        video = Video("v", "https://youtube.com/watch?v=v", "T", "U", null, null, 60, 0, null),
        description = null, hlsUrl = null, dashUrl = null,
        videoStreams = video, audioStreams = audio, relatedVideos = emptyList(), likeCount = null
    )

    private val sample = info(
        video = listOf(
            VideoStreamOption("v360", "360p", "MPEG_4", isVideoOnly = false),
            VideoStreamOption("v720", "720p", "MPEG_4", isVideoOnly = false),
            VideoStreamOption("v1080only", "1080p", "MPEG_4", isVideoOnly = true)
        ),
        audio = listOf(
            AudioStreamOption("a48", 48, "WEBMA"),
            AudioStreamOption("a128", 128, "M4A")
        )
    )

    @Test
    fun `lists HD merge first, then muxed video highest first, then audio by bitrate`() {
        val opts = DownloadOptions.build(sample)
        assertEquals(listOf("v1080only", "v720", "v360", "a128", "a48"), opts.map { it.url })
        assertEquals("1080p · MP4 (HD)", opts[0].label)
        assertTrue(opts[0].isMerged)
        assertEquals("a128", opts[0].audioUrl)   // joined with the M4A, not the Opus track
        assertEquals("720p · MP4", opts[1].label)
        assertEquals("128 kbps · M4A", opts[3].label)
        assertEquals("webm", opts[4].extension)
    }

    @Test
    fun `video-only streams that are not H264 are not merged into an MP4`() {
        val i = info(
            video = listOf(
                VideoStreamOption("v360", "360p", "MPEG_4", false),
                VideoStreamOption("av1", "1080p", "MPEG_4", true, codec = "av01.0.08M.08")
            ),
            audio = listOf(AudioStreamOption("a128", 128, "M4A"))
        )
        assertTrue(DownloadOptions.build(i).none { it.isMerged })
    }

    @Test
    fun `VP9 video-only plus Opus is offered as a WebM when there is no H264 at that height`() {
        val i = info(
            video = listOf(
                VideoStreamOption("v360", "360p", "MPEG_4", false),
                VideoStreamOption("vp9_1440", "1440p", "WEBM", true, codec = "vp9"),
                VideoStreamOption("vp9_1080", "1080p", "WEBM", true, codec = "vp9")
            ),
            audio = listOf(AudioStreamOption("opus", 160, "WEBMA_OPUS"))
        )
        val hd = DownloadOptions.build(i).filter { it.isMerged }
        assertEquals(listOf("1440p · WebM (HD)", "1080p · WebM (HD)"), hd.map { it.label })
        assertEquals("webm", hd[0].extension)
        assertEquals("opus", hd[0].audioUrl)
    }

    @Test
    fun `H264 MP4 wins over WebM at the same height`() {
        val i = info(
            video = listOf(
                VideoStreamOption("v360", "360p", "MPEG_4", false),
                VideoStreamOption("avc1080", "1080p", "MPEG_4", true, codec = "avc1.640028"),
                VideoStreamOption("vp9_1080", "1080p", "WEBM", true, codec = "vp9")
            ),
            audio = listOf(AudioStreamOption("a128", 128, "M4A"), AudioStreamOption("opus", 160, "WEBMA_OPUS"))
        )
        assertEquals(listOf("avc1080"), DownloadOptions.build(i).filter { it.isMerged }.map { it.url })
    }

    @Test
    fun `diagnostic explains a 360p-only result and is null when HD exists`() {
        assertEquals(null, DownloadOptions.hdDiagnostic(sample))
        val none = info(video = listOf(VideoStreamOption("v360", "360p", "MPEG_4", false)), audio = listOf(AudioStreamOption("a", 128, "M4A")))
        assertTrue(DownloadOptions.hdDiagnostic(none)!!.contains("no separate HD video streams"))
        val noAudio = info(video = listOf(VideoStreamOption("v360", "360p", "MPEG_4", false), VideoStreamOption("x", "1080p", "MPEG_4", true)), audio = emptyList())
        assertTrue(DownloadOptions.hdDiagnostic(noAudio)!!.contains("no usable audio"))
    }

    @Test
    fun `match finds the same stream by format key in fresh info`() {
        val fresh = info(
            video = listOf(VideoStreamOption("new720", "720p", "MPEG_4", false), VideoStreamOption("new360", "360p", "MPEG_4", false)),
            audio = emptyList()
        )
        assertEquals("new360", DownloadOptions.match(fresh, "v|360p|MPEG_4", audioOnly = false)?.url)
    }

    @Test
    fun `match falls back to the next lower quality of the same kind`() {
        val fresh = info(video = listOf(VideoStreamOption("new360", "360p", "MPEG_4", false)), audio = listOf(AudioStreamOption("a", 128, "M4A")))
        assertEquals("new360", DownloadOptions.match(fresh, "v|720p|MPEG_4", audioOnly = false)?.url)
        assertEquals("a", DownloadOptions.match(fresh, "a|160|WEBMA", audioOnly = true)?.url)
    }
}
