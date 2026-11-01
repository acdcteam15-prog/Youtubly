package com.youtubly.app.playback

import android.content.Context

/**
 * Remembers (per device, not backed up) that this phone's graphics driver can't run Media3's
 * video-effect pipeline. On some drivers the final render to the screen fails with
 * "glError: invalid operation" in GlUtil.clearFocusedBuffers — for every video and resolution
 * (seen on Samsung SM-A376B / Android 16). Once that happens the player's video renderer stays
 * in effects mode and can't show any later video, so we stop using the pipeline for good.
 */
object GpuEffects {
    private const val PREFS = "gpu_effects"
    private const val KEY = "broken"

    private const val KEY_VERSION = "broken_version"
    private const val KEY_SUSPENDED_UNTIL = "suspended_until"

    /**
     * True while video blur must stay off: a confirmed driver failure (only for the app version that saw it,
     * so every update gets a fresh try with new Media3/driver code) or a temporary suspension after repeated
     * freezes. Marks written by older builds carry no version and count as stale.
     */
    fun isBroken(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() < p.getLong(KEY_SUSPENDED_UNTIL, 0L)) return true
        if (!p.getBoolean(KEY, false)) return false
        return p.getInt(KEY_VERSION, -1) == com.youtubly.app.BuildConfig.VERSION_CODE
    }

    fun markBroken(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY, true).putInt(KEY_VERSION, com.youtubly.app.BuildConfig.VERSION_CODE).commit()

    /** Pause the effects pipeline for [durationMs] (repeated freezes) without condemning it for good. */
    fun suspendFor(context: Context, durationMs: Long) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SUSPENDED_UNTIL, System.currentTimeMillis() + durationMs).commit()

    /** For Settings → Diagnostics, if the user updates the phone/app and wants to try again. */
    fun reset(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).remove(KEY_VERSION).remove(KEY_SUSPENDED_UNTIL).remove(KEY_WEDGES).commit()

    /** True for the driver failure (not for things like an HDR format the effect can't handle). */
    fun isDriverFailure(t: Throwable?): Boolean {
        var e = t
        while (e != null) {
            if (e.javaClass.simpleName == "GlException" && e.stackTrace.any { it.methodName == "clearFocusedBuffers" || it.methodName == "renderFrameToOutputSurface" }) return true
            e = e.cause
        }
        return false
    }

    /**
     * Media3's effects pipeline crashed while flushing: ExternalTextureManager.removeAllSurfaceTextureFrames
     * throws NoSuchElementException when the decoder already produced frames that weren't registered yet
     * (a race when the player is stopped / re-prepared mid-decode). Surfaces as ERROR_CODE_UNSPECIFIED
     * "Unexpected runtime error", so it never matched the frame-processing check.
     */
    fun isEffectsCrash(t: Throwable?): Boolean {
        var e = t
        while (e != null) {
            if (e.stackTrace.take(4).any { it.className.startsWith("androidx.media3.effect.") } &&
                (e is NoSuchElementException || e is IllegalStateException || e is IllegalArgumentException)) return true
            e = e.cause
        }
        return false
    }

    /**
     * "Detaching surface timed out": the playback thread is blocked (stuck releasing the effects
     * pipeline), so the player can't be recovered in-process. It is NOT a network timeout.
     */
    fun isRendererWedged(t: Throwable?): Boolean {
        var e = t
        while (e != null) {
            if (e.javaClass.simpleName == "ExoTimeoutException") return true
            e = e.cause
        }
        return false
    }

    private const val KEY_WEDGES = "wedge_times"
    private const val WEDGE_WINDOW_MS = 10 * 60_000L

    /**
     * Records a wedge and returns true when it keeps happening (2+ in 10 minutes): then the effects
     * pipeline is paused for a while (see [suspendFor]) instead of restart-looping.
     */
    fun recordWedgeShouldDisable(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = (prefs.getString(KEY_WEDGES, "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it < WEDGE_WINDOW_MS } + now
        prefs.edit().putString(KEY_WEDGES, recent.joinToString(",")).commit()
        return recent.size >= 2
    }

    /** Relaunches the app so the player (and its stuck video renderer) is created fresh. */
    fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
