package com.youtubly.app.playback

import com.youtubly.app.data.local.datastore.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bedtime mode. Between the start and end times (a window that may cross midnight, e.g.
 * 22:00 → 07:00) the app switches to a dark, warm theme and caps the player's volume.
 * Re-checked at every minute boundary, so it starts and ends on time without a restart.
 *
 * The cap is applied through ExoPlayer's own volume (0–1), so it only affects Youtubly and
 * never changes the phone's media volume.
 */
@Singleton
class Bedtime @Inject constructor(
    userPreferences: UserPreferences,
    private val controller: PlayerController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val minuteTicks = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }

    val active: StateFlow<Boolean> = combine(
        userPreferences.bedtimeOn,
        userPreferences.bedtimeStartMin,
        userPreferences.bedtimeEndMin,
        minuteTicks
    ) { on, start, end, now -> on && inWindow(minuteOfDay(now), start, end) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            combine(active, userPreferences.bedtimeVolumePct) { a, pct -> if (a) pct / 100f else 1f }
                .distinctUntilChanged()
                .collect { controller.player.volume = it }
        }
    }

    companion object {
        fun minuteOfDay(now: Long): Int = Calendar.getInstance().apply { timeInMillis = now }.let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }

        /** [start] inclusive, [end] exclusive; start > end means the window crosses midnight. */
        fun inWindow(minute: Int, start: Int, end: Int): Boolean = when {
            start == end -> false
            start < end -> minute in start until end
            else -> minute >= start || minute < end
        }
    }
}
