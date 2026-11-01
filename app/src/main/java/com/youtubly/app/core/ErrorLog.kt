package com.youtubly.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** One recorded failure. [area] is a short label ("Request", "Crash", "Player"...). */
data class ErrorEntry(val timeMs: Long, val area: String, val type: String, val message: String, val trace: String)

/**
 * Keeps the last [MAX] errors in memory and in `filesDir/diagnostics.log`, so the Diagnostics
 * page can show what went wrong even after a restart or a crash.
 *
 * It's a plain object (not Hilt) because it's called from [safeIoCall] and the crash handler,
 * neither of which has an injector. Call [init] once from Application.onCreate.
 */
object ErrorLog {
    const val MAX = 50
    private const val TAG = "Youtubly"
    private var file: File? = null
    private val _entries = MutableStateFlow<List<ErrorEntry>>(emptyList())
    val entries: StateFlow<List<ErrorEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        val f = File(context.filesDir, "diagnostics.log")
        file = f
        _entries.value = runCatching { ErrorLogCodec.decode(f.readText()) }.getOrDefault(emptyList())
        installCrashHandler()
    }

    fun record(area: String, t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) return
        val root = generateSequence(t) { it.cause }.last()
        add(ErrorEntry(
            timeMs = System.currentTimeMillis(),
            area = area,
            type = root::class.java.simpleName,
            message = (root.message ?: t.message ?: "").take(500),
            trace = t.stackTraceToString().lineSequence().take(12).joinToString("\n"),
        ))
    }

    fun clear() {
        _entries.value = emptyList()
        runCatching { file?.delete() }
    }

    /** Plain-text report for the clipboard. */
    fun report(header: String): String = buildString {
        appendLine(header)
        appendLine("Errors: ${_entries.value.size}")
        appendLine()
        _entries.value.forEach { e ->
            appendLine("[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(e.timeMs)}] ${e.area} · ${e.type}")
            if (e.message.isNotBlank()) appendLine(e.message)
            appendLine(e.trace)
            appendLine()
        }
    }

    @Synchronized private fun add(e: ErrorEntry) {
        _entries.value = (listOf(e) + _entries.value).take(MAX)
        runCatching { file?.writeText(ErrorLogCodec.encode(_entries.value)) }
            .onFailure { Log.w(TAG, "Couldn't write diagnostics log", it) }
    }

    /** Records the crash, then lets Android's default handler show the crash as usual. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    private class CrashHandler(val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, e: Throwable) {
            runCatching { record("Crash", e) }
            previous?.uncaughtException(thread, e)
        }
    }
}

/**
 * Line-based storage for [ErrorEntry] lists. Fields are escaped so newlines in traces survive.
 * Pure Kotlin (no Android) so it's covered by JVM tests.
 */
object ErrorLogCodec {
    private const val SEP = "\u001F"
    private const val REC = "\u001E"

    fun encode(list: List<ErrorEntry>): String = list.joinToString(REC) { e ->
        listOf(e.timeMs.toString(), e.area, e.type, e.message, e.trace).joinToString(SEP) { esc(it) }
    }

    fun decode(text: String): List<ErrorEntry> =
        if (text.isBlank()) emptyList()
        else text.split(REC).mapNotNull { rec ->
            val p = rec.split(SEP)
            if (p.size != 5) return@mapNotNull null
            val t = p[0].toLongOrNull() ?: return@mapNotNull null
            ErrorEntry(t, unesc(p[1]), unesc(p[2]), unesc(p[3]), unesc(p[4]))
        }.take(ErrorLog.MAX)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(SEP, "\\u").replace(REC, "\\r")
    private fun unesc(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> out.append('\\')
                    'u' -> out.append(SEP)
                    'r' -> out.append(REC)
                    else -> { out.append(c); out.append(s[i + 1]) }
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
