package com.youtubly.app.core

/** Decides whether the app is crashing again and again (pure, unit-tested). */
object CrashLoop {
    const val WINDOW_MS = 10 * 60_000L
    const val THRESHOLD = 2

    /** True when at least [threshold] crashes happened within the last [windowMs]. */
    fun isLoop(crashTimes: List<Long>, nowMs: Long, windowMs: Long = WINDOW_MS, threshold: Int = THRESHOLD): Boolean =
        crashTimes.count { nowMs - it in 0..windowMs } >= threshold

    /** Keeps only recent crashes (and at most 10) so the list never grows. */
    fun prune(crashTimes: List<Long>, nowMs: Long, windowMs: Long = WINDOW_MS): List<Long> =
        crashTimes.filter { nowMs - it in 0..windowMs }.takeLast(10)
}
