package com.youtubly.app.core

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private const val TAG = "Youtubly"

suspend fun <T> safeIoCall(
    dispatchers: DispatcherProvider,
    block: suspend () -> T
): Result<T> = withContext(dispatchers.io) {
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        // Always visible in Logcat (filter by tag "Youtubly") so failures are never silent.
        Log.e(TAG, "Request failed: ${t::class.java.simpleName}: ${t.message}", t)
        ErrorLog.record("Request", t)
        Result.failure(t)
    }
}

/** Human-readable message even when an exception has no message. Recognizes the common
 *  no-connectivity exception shapes and returns a plain "you're offline" message for those,
 *  instead of surfacing a raw string like "Unable to resolve host" that doesn't tell the
 *  user what's actually wrong. */
fun Throwable.readable(): String {
    val chain = generateSequence(this) { it.cause }.take(10).toList()
    val root = chain.last()
    // Check the WHOLE cause chain: an UnknownHostException's own cause is Android's GaiException
    // ("android_getaddrinfo failed: EAI_NODATA…"), so looking only at the root missed it and the raw
    // resolver text was shown to the user. Offline must also win over "extractor broke" messages.
    if (chain.any { it.isConnectivityFailure() }) return "You're offline — check your connection and try again"
    ExtractorHealth.message(this)?.let { return it }
    val msg = root.message ?: message
    return if (msg.isNullOrBlank()) root::class.java.simpleName else msg
}

private fun Throwable.isConnectivityFailure(): Boolean = when (this) {
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.net.SocketTimeoutException,
    is java.net.NoRouteToHostException -> true
    else -> javaClass.simpleName == "GaiException" ||
        (this is java.io.IOException && message?.let {
            it.contains("Unable to resolve host", true) || it.contains("getaddrinfo", true) || it.contains("No address associated", true)
        } == true)
}
