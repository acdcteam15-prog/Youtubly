package com.youtubly.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sleep timer: pauses playback after a delay, or at the end of the current video, fading the
 * volume out over the last [SleepFade.FADE_MS] instead of cutting off.
 *
 * Singleton on the app's player, so it keeps running with the screen off, in PiP, or when the
 * player sheet is closed. The volume it fades from is read when the fade starts, so it respects
 * Bedtime's quieter volume, and it's restored after pausing so the next video isn't silent.
 */
@Singleton
class SleepTimer @Inject constructor(private val controller: PlayerController) {

    sealed interface State {
        data object Off : State
        /** [remainingMs] updates every second. [endOfVideo] = stops when this video ends. */
        data class Running(val remainingMs: Long, val endOfVideo: Boolean) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    fun startMinutes(minutes: Int) = start(endOfVideo = false, durationMs = minutes * 60_000L)
    fun startEndOfVideo() = start(endOfVideo = true, durationMs = 0L)

    fun cancel() {
        job?.cancel(); job = null
        _state.value = State.Off
    }

    private fun start(endOfVideo: Boolean, durationMs: Long) {
        job?.cancel()
        val deadline = SystemClock.elapsedRealtime() + durationMs
        job = scope.launch {
            val player = controller.player
            var baseVolume: Float? = null
            try {
                while (isActive) {
                    val remaining = if (endOfVideo) {
                        val d = player.duration
                        if (d <= 0) Long.MAX_VALUE else (d - player.currentPosition).coerceAtLeast(0)
                    } else (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    _state.value = State.Running(if (remaining == Long.MAX_VALUE) 0 else remaining, endOfVideo)

                    if (remaining <= SleepFade.FADE_MS && player.isPlaying) {
                        if (baseVolume == null) baseVolume = player.volume
                        player.volume = baseVolume * SleepFade.volumeFactor(remaining)
                    }
                    // End-of-video pauses just before the end, so autoplay never gets to start the next one.
                    if (remaining == 0L || (endOfVideo && remaining <= 500)) {
                        player.pause()
                        break
                    }
                    delay(if (remaining <= SleepFade.FADE_MS) 200 else 1000)
                }
            } finally {
                baseVolume?.let { player.volume = it }
                _state.value = State.Off
            }
        }
    }
}

/** Pure fade curve (unit-tested). */
object SleepFade {
    const val FADE_MS = 30_000L
    /** 1.0 until the fade starts, then eases down to 0 (squared, so it sounds even to the ear). */
    fun volumeFactor(remainingMs: Long): Float {
        if (remainingMs >= FADE_MS) return 1f
        val t = (remainingMs.coerceAtLeast(0).toFloat() / FADE_MS)
        return t * t
    }
}
