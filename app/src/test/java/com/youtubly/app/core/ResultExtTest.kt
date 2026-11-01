package com.youtubly.app.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ResultExtTest {

    private val dispatchers = DispatcherProvider()

    @Test
    fun `safeIoCall wraps a successful block in Result_success`() = runTest {
        val result = safeIoCall(dispatchers) { 42 }
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `safeIoCall catches a thrown exception into Result_failure instead of propagating it`() = runTest {
        val result = safeIoCall(dispatchers) { throw IllegalStateException("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun `safeIoCall rethrows CancellationException rather than swallowing it into Result_failure`() = runTest {
        // Swallowing cancellation would break structured concurrency (the coroutine wouldn't
        // actually stop) — safeIoCall must let it propagate rather than wrapping it.
        safeIoCall(dispatchers) { throw kotlinx.coroutines.CancellationException("cancelled") }
    }

    @Test
    fun `readable returns a friendly offline message for UnknownHostException`() {
        val e = UnknownHostException("Unable to resolve host \"www.youtube.com\"")
        assertEquals("You're offline — check your connection and try again", e.readable())
    }

    @Test
    fun `readable recognizes every common no-connectivity exception type`() {
        val offlineMessage = "You're offline — check your connection and try again"
        assertEquals(offlineMessage, ConnectException().readable())
        assertEquals(offlineMessage, SocketTimeoutException().readable())
        assertEquals(offlineMessage, NoRouteToHostException().readable())
    }

    @Test
    fun `readable does not misclassify an unrelated exception as offline`() {
        val e = IllegalStateException("No playable stream found for this video")
        assertEquals("No playable stream found for this video", e.readable())
    }

    @Test
    fun `readable walks the cause chain to find a connectivity failure`() {
        val wrapped = RuntimeException("player init failed", UnknownHostException("no dns"))
        assertEquals("You're offline — check your connection and try again", wrapped.readable())
    }

    @Test
    fun `readable falls back to the exception's simple class name when there's no message anywhere`() {
        class NoMessageException : RuntimeException()
        assertEquals("NoMessageException", NoMessageException().readable())
    }

    @Test
    fun `readable falls back to the root cause's class name over a blank message`() {
        class BlankMessageException : RuntimeException("")
        assertEquals("BlankMessageException", BlankMessageException().readable())
    }

    @Test
    fun `readable detects offline when the root cause is the resolver error behind UnknownHostException`() {
        // Android: UnknownHostException -> GaiException("android_getaddrinfo failed: EAI_NODATA ...").
        class GaiException(msg: String) : RuntimeException(msg)
        val e = UnknownHostException("Unable to resolve host \"youtubei.googleapis.com\"")
            .apply { initCause(GaiException("android_getaddrinfo failed: EAI_NODATA (No address associated with hostname)")) }
        assertEquals("You're offline — check your connection and try again", e.readable())
    }
}
