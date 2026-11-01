package com.youtubly.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuEffectsTest {

    private fun crashFrom(cls: String): Throwable =
        NoSuchElementException().apply {
            stackTrace = arrayOf(
                StackTraceElement("java.util.AbstractQueue", "remove", "AbstractQueue.java", 117),
                StackTraceElement(cls, "removeAllSurfaceTextureFrames", "ExternalTextureManager.java", 393),
            )
        }

    @Test
    fun `effects pipeline race is recognised even when wrapped by the player`() {
        val wrapped = RuntimeException("Unexpected runtime error", crashFrom("androidx.media3.effect.ExternalTextureManager"))
        assertTrue(GpuEffects.isEffectsCrash(wrapped))
    }

    @Test
    fun `same exception from app code is not an effects crash`() {
        assertFalse(GpuEffects.isEffectsCrash(crashFrom("com.youtubly.app.SomethingElse")))
    }

    // Stand-in with the real class's simple name (the real one lives in media3-exoplayer).
    private class ExoTimeoutException(msg: String) : RuntimeException(msg)

    @Test
    fun `detaching-surface timeout is a wedged renderer, not a network error`() {
        val e = RuntimeException("Unexpected runtime error", ExoTimeoutException("Detaching surface timed out."))
        assertTrue(GpuEffects.isRendererWedged(e))
        assertFalse(GpuEffects.isRendererWedged(java.net.SocketTimeoutException()))
    }
}
