package com.youtubly.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorLogCodecTest {
    private fun e(t: Long, msg: String, trace: String = "at a.b(C.kt:1)\nat d.e(F.kt:2)") =
        ErrorEntry(t, "Request", "IOException", msg, trace)

    @Test fun roundTripKeepsNewlinesAndSeparators() {
        val tricky = e(1, "line1\nline2 \\ back\u001Fsep\u001Erec")
        val out = ErrorLogCodec.decode(ErrorLogCodec.encode(listOf(tricky, e(2, "plain"))))
        assertEquals(listOf(tricky, e(2, "plain")), out)
    }

    @Test fun emptyAndGarbageDecodeToEmpty() {
        assertTrue(ErrorLogCodec.decode("").isEmpty())
        assertTrue(ErrorLogCodec.decode("not a log at all").isEmpty())
    }

    @Test fun decodeCapsAtMax() {
        val many = (1..ErrorLog.MAX + 20).map { e(it.toLong(), "m$it") }
        assertEquals(ErrorLog.MAX, ErrorLogCodec.decode(ErrorLogCodec.encode(many)).size)
    }
}
