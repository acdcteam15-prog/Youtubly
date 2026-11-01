package com.youtubly.app.playback

import com.youtubly.app.domain.model.AudioStreamOption
import com.youtubly.app.domain.model.StreamInfoResult
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.model.VideoStreamOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testVideo() = Video(
    id = "v1",
    url = "https://youtube.com/watch?v=v1",
    title = "Test video",
    uploaderName = "Someone",
    uploaderUrl = null,
    thumbnailUrl = null,
    duration = 120,
    viewCount = 0,
    uploadDate = null
)

private fun infoWith(
    hlsUrl: String? = null,
    dashUrl: String? = null,
    videoStreams: List<VideoStreamOption> = emptyList(),
    audioStreams: List<AudioStreamOption> = emptyList()
) = StreamInfoResult(
    video = testVideo(),
    description = null,
    hlsUrl = hlsUrl,
    dashUrl = dashUrl,
    videoStreams = videoStreams,
    audioStreams = audioStreams,
    relatedVideos = emptyList(),
    likeCount = null
)

class StreamSourcePlanTest {

    // --- selectBestAudio ---

    @Test
    fun `selectBestAudio prefers M4A even when a higher-bitrate non-M4A track exists`() {
        val audios = listOf(
            AudioStreamOption("opus.url", averageBitrate = 160, format = "OPUS"),
            AudioStreamOption("m4a.url", averageBitrate = 128, format = "M4A")
        )
        assertEquals("m4a.url", selectBestAudio(audios)?.url)
    }

    @Test
    fun `selectBestAudio falls back to highest bitrate when no M4A track exists`() {
        val audios = listOf(
            AudioStreamOption("low.url", averageBitrate = 64, format = "OPUS"),
            AudioStreamOption("high.url", averageBitrate = 160, format = "OPUS")
        )
        assertEquals("high.url", selectBestAudio(audios)?.url)
    }

    @Test
    fun `selectBestAudio returns null when there are no audio streams`() {
        assertNull(selectBestAudio(emptyList()))
    }

    // --- selectAutoVideo ---

    @Test
    fun `selectAutoVideo picks the highest MPEG_4 height at or below 1080p`() {
        val streams = listOf(
            VideoStreamOption("2160p.url", "2160p", "MPEG_4", isVideoOnly = true),
            VideoStreamOption("1080p.url", "1080p", "MPEG_4", isVideoOnly = true),
            VideoStreamOption("720p.url", "720p", "MPEG_4", isVideoOnly = true)
        )
        // 2160p must be excluded even though it's the highest available — the 1080p cap exists
        // because higher usually means VP9/AV1, which a phone may not decode as reliably.
        assertEquals("1080p.url", selectAutoVideo(streams)?.url)
    }

    @Test
    fun `selectAutoVideo ignores non-MPEG_4 formats`() {
        val streams = listOf(
            VideoStreamOption("vp9.url", "1080p", "VP9", isVideoOnly = true),
            VideoStreamOption("mp4.url", "480p", "MPEG_4", isVideoOnly = true)
        )
        assertEquals("mp4.url", selectAutoVideo(streams)?.url)
    }

    @Test
    fun `selectAutoVideo ignores muxed (non video-only) streams`() {
        val streams = listOf(VideoStreamOption("muxed.url", "1080p", "MPEG_4", isVideoOnly = false))
        assertNull(selectAutoVideo(streams))
    }

    // --- selectVideoAtHeight ---

    @Test
    fun `selectVideoAtHeight prefers MPEG_4 over other containers at the same height`() {
        val streams = listOf(
            VideoStreamOption("vp9.url", "720p", "VP9", isVideoOnly = true),
            VideoStreamOption("mp4.url", "720p", "MPEG_4", isVideoOnly = true)
        )
        assertEquals("mp4.url", selectVideoAtHeight(streams, 720)?.url)
    }

    @Test
    fun `selectVideoAtHeight returns null when the exact height isn't available`() {
        val streams = listOf(VideoStreamOption("720p.url", "720p", "MPEG_4", isVideoOnly = true))
        assertNull(selectVideoAtHeight(streams, 1080))
    }

    // --- buildStreamPlans: ordering ---

    @Test
    fun `auto quality orders HLS, then DASH, then video+audio, then legacy progressive`() {
        val info = infoWith(
            hlsUrl = "hls.url",
            dashUrl = "dash.url",
            videoStreams = listOf(
                VideoStreamOption("video.url", "1080p", "MPEG_4", isVideoOnly = true),
                VideoStreamOption("muxed.url", "360p", "MPEG_4", isVideoOnly = false)
            ),
            audioStreams = listOf(AudioStreamOption("audio.url", 128, "M4A"))
        )
        val plans = buildStreamPlans(info, PlaybackOptions())

        assertEquals(4, plans.size)
        assertTrue(plans[0] is StreamPlan.Hls)
        assertTrue(plans[1] is StreamPlan.Dash)
        assertTrue(plans[2] is StreamPlan.VideoPlusAudio)
        assertTrue(plans[3] is StreamPlan.Progressive)
    }

    @Test
    fun `fixed quality puts the requested height first, ahead of the auto chain`() {
        val info = infoWith(
            hlsUrl = "hls.url",
            videoStreams = listOf(
                VideoStreamOption("1080p.url", "1080p", "MPEG_4", isVideoOnly = true),
                VideoStreamOption("480p.url", "480p", "MPEG_4", isVideoOnly = true)
            ),
            audioStreams = listOf(AudioStreamOption("audio.url", 128, "M4A"))
        )
        val plans = buildStreamPlans(info, PlaybackOptions(qualityHeight = 480))

        assertTrue(plans[0] is StreamPlan.VideoPlusAudio)
        assertEquals("480p.url", (plans[0] as StreamPlan.VideoPlusAudio).videoUrl)
        // HLS (auto quality, ignores the fixed request) still follows as a fallback.
        assertTrue(plans[1] is StreamPlan.Hls)
    }

    @Test
    fun `fixed quality falls through to auto chain when the requested height isn't available`() {
        val info = infoWith(
            hlsUrl = "hls.url",
            videoStreams = listOf(VideoStreamOption("1080p.url", "1080p", "MPEG_4", isVideoOnly = true)),
            audioStreams = listOf(AudioStreamOption("audio.url", 128, "M4A"))
        )
        val plans = buildStreamPlans(info, PlaybackOptions(qualityHeight = 2160))

        // No VideoPlusAudio at 2160p — HLS is the first (and only) candidate.
        assertEquals(1, plans.size)
        assertTrue(plans[0] is StreamPlan.Hls)
    }

    @Test
    fun `no candidates at all when the video has neither adaptive streams nor a muxed fallback`() {
        val info = infoWith(audioStreams = listOf(AudioStreamOption("audio.url", 128, "M4A")))
        assertTrue(buildStreamPlans(info, PlaybackOptions()).isEmpty())
    }

    // --- buildStreamPlans: audio-only ---

    @Test
    fun `audio-only returns the best audio track first, second-best as the only fallback`() {
        val info = infoWith(
            hlsUrl = "hls.url", // must be ignored entirely when audioOnly is requested
            audioStreams = listOf(
                AudioStreamOption("low.url", 64, "M4A"),
                AudioStreamOption("high.url", 160, "M4A")
            )
        )
        val plans = buildStreamPlans(info, PlaybackOptions(audioOnly = true))

        assertEquals(2, plans.size)
        assertEquals("high.url", (plans[0] as StreamPlan.Progressive).url)
        assertEquals("low.url", (plans[1] as StreamPlan.Progressive).url)
    }

    @Test
    fun `audio-only with a single audio track has no fallback candidate`() {
        val info = infoWith(audioStreams = listOf(AudioStreamOption("only.url", 128, "M4A")))
        val plans = buildStreamPlans(info, PlaybackOptions(audioOnly = true))
        assertEquals(1, plans.size)
    }

    @Test
    fun `audio-only with no audio streams at all returns no candidates, not a silent video fallback`() {
        // Regression guard: the original inline logic's `if (audioOnly && bestAudio != null)`
        // would silently fall through into building *video* candidates here instead of
        // reporting "nothing playable" — this must stay explicitly empty.
        val info = infoWith(
            hlsUrl = "hls.url",
            videoStreams = listOf(VideoStreamOption("video.url", "1080p", "MPEG_4", isVideoOnly = true))
        )
        val plans = buildStreamPlans(info, PlaybackOptions(audioOnly = true))
        assertTrue(plans.isEmpty())
    }

    // --- network quality cap (Phase 1) ---

    @Test
    fun `selectAutoVideo respects a network cap`() {
        val streams = listOf(
            VideoStreamOption("1080p.url", "1080p", "MPEG_4", isVideoOnly = true),
            VideoStreamOption("720p.url", "720p", "MPEG_4", isVideoOnly = true),
            VideoStreamOption("360p.url", "360p", "MPEG_4", isVideoOnly = true)
        )
        assertEquals("720p.url", selectAutoVideo(streams, maxHeight = 720)?.url)
        assertEquals("360p.url", selectAutoVideo(streams, maxHeight = 480)?.url)
    }

    @Test
    fun `selectAutoVideo falls back to the smallest stream when the cap is below everything`() {
        val streams = listOf(
            VideoStreamOption("720p.url", "720p", "MPEG_4", isVideoOnly = true),
            VideoStreamOption("480p.url", "480p", "MPEG_4", isVideoOnly = true)
        )
        assertEquals("480p.url", selectAutoVideo(streams, maxHeight = 360)?.url)
    }

    @Test
    fun `capped auto quality uses the capped video-only stream in the video+audio plan`() {
        val info = infoWith(
            videoStreams = listOf(
                VideoStreamOption("1080p.url", "1080p", "MPEG_4", isVideoOnly = true),
                VideoStreamOption("360p.url", "360p", "MPEG_4", isVideoOnly = true)
            ),
            audioStreams = listOf(AudioStreamOption("a.url", averageBitrate = 128, format = "M4A"))
        )
        val plans = buildStreamPlans(info, PlaybackOptions(maxHeight = 360))
        assertEquals(StreamPlan.VideoPlusAudio("360p.url", "a.url"), plans.first())
    }
}
