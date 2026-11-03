package com.example.remoty

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** A small in-memory log of what the app did (kept for the Diagnostics button and crash reports). */
object AppLog {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun i(msg: String) {
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > 300) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}

/** The connection the notification buttons use (they run outside the screens). */
object Session {
    @Volatile var api: Api? = null
}
