package com.youtubly.app.core

/** "Low battery: listen only" (pure, unit-tested). */
object LowBattery {
    /** True when the battery is at or below [thresholdPct], not charging, and the rule is on (threshold above 0). */
    fun listenOnly(levelPct: Int, charging: Boolean, thresholdPct: Int): Boolean =
        thresholdPct > 0 && !charging && levelPct in 0..thresholdPct

    /** Battery level in percent from the raw Android values, or -1 when unknown. */
    fun percent(level: Int, scale: Int): Int = if (level < 0 || scale <= 0) -1 else (level * 100 / scale).coerceIn(0, 100)
}
