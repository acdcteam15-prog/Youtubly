package com.youtubly.app.blur.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.youtubly.app.blur.BlurEngine
import com.youtubly.app.blur.channelKeyOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between the blur engine and ExoPlayer's video-effects pipeline (media3-effect).
 * PlayerController calls [effectsFor] right before each prepare().
 *
 * The effect is installed whenever blur + playback are on at load time; after that every
 * change (whitelist, genders, style, strength, turning blur off) applies live per frame.
 * Turning blur ON mid-video applies from the next video (installing effects needs a re-prepare).
 */
@Singleton
class PlaybackBlur @Inject constructor(private val engine: BlurEngine) {
    private val tracker = FaceTracker(engine)

    @OptIn(UnstableApi::class)
    private val effect = object : GlEffect {
        // useHdr must be honoured: high-quality (Wi-Fi) streams can be HDR / 10-bit, and an 8-bit-only
        // program in an HDR pipeline fails with "video frame processing failed".
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = FaceBlurShaderProgram(tracker, useHdr)
    }

    /** @param uploaderUrl channel of the video about to play (null = unknown → blurred, fail-closed).
     *  Excluded channels get no effect at all, so they never touch the GPU pipeline. */
    fun effectsFor(uploaderUrl: String?, audioOnly: Boolean, videoId: String? = null): List<Effect> {
        tracker.channelKey = channelKeyOf(uploaderUrl)
        tracker.videoId = videoId
        tracker.reset()
        val s = engine.settings.value
        return if (!audioOnly && blurNeeded(uploaderUrl, videoId)) listOf(effect) else emptyList()
    }

    /** Would this video actually be blurred (blur on, channel not whitelisted…)? */
    fun blurNeeded(uploaderUrl: String?, videoId: String?): Boolean {
        val s = engine.settings.value
        if (!(s.enabled && s.applyToPlayback && s.needsDetection)) return false
        return engine.shouldProcess(channelKeyOf(uploaderUrl), com.youtubly.app.blur.BlurSurface.PLAYBACK, videoId = videoId)
    }
}
