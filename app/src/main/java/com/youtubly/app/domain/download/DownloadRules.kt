package com.youtubly.app.domain.download

import java.util.Calendar
import java.util.TimeZone

/**
 * User rules for when downloads may run.
 *  - [wifiOnly]: only on unmetered networks.
 *  - [minBatteryPercent]: pause below this level unless charging (0 = no limit).
 *  - [scheduleEnabled] + [startHour]..[endHour]: new downloads start only inside this window (e.g. 1→7 = overnight).
 */
data class DownloadRules(
    val wifiOnly: Boolean = false,
    val minBatteryPercent: Int = 0,
    val scheduleEnabled: Boolean = false,
    val startHour: Int = 1,
    val endHour: Int = 7
) {
    /** Short reason a queued item is waiting, for the list ("Waiting for Wi-Fi"), or null when no rule applies. */
    fun queuedHint(): String? {
        val parts = buildList {
            if (wifiOnly) add("Wi-Fi")
            if (scheduleEnabled) add("%02d:00–%02d:00".format(startHour, endHour))
            if (minBatteryPercent > 0) add("battery ≥ $minBatteryPercent%")
        }
        return if (parts.isEmpty()) null else "Waiting for " + parts.joinToString(" · ")
    }

    companion object {
        val BATTERY_STEPS = listOf(0, 20, 30, 50)

        /** Milliseconds until the window opens, 0 when [nowMs] is already inside it (or the schedule is off). */
        fun delayUntilWindowMs(rules: DownloadRules, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Long {
            if (!rules.scheduleEnabled || rules.startHour == rules.endHour) return 0L
            val cal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val inside = if (rules.startHour < rules.endHour) hour in rules.startHour until rules.endHour
            else hour >= rules.startHour || hour < rules.endHour   // window wraps past midnight (e.g. 22 → 6)
            if (inside) return 0L
            val next = Calendar.getInstance(tz).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, rules.startHour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
            }
            return next.timeInMillis - nowMs
        }
    }
}
