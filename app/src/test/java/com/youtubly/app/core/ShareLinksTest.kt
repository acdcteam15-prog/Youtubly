package com.youtubly.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareLinksTest {
    private val raw = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&pp=ygUEcmljaw%3D%3D&si=abc123&feature=shared&t=42"

    @Test fun cleanKeepsOnlyUsefulParams() =
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42", ShareLinks.clean(raw))

    @Test fun shortLink() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=42", ShareLinks.short(raw))
        assertEquals("https://youtu.be/dQw4w9WgXcQ", ShareLinks.short("https://youtu.be/dQw4w9WgXcQ?si=zzz"))
        assertEquals("https://youtu.be/abcdefghijk", ShareLinks.short("https://www.youtube.com/shorts/abcdefghijk?feature=share"))
    }

    @Test fun startTimeReplacesAndRemoves() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=75s", ShareLinks.build(raw, short = true, startSec = 75))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", ShareLinks.build(raw, short = false, startSec = 0))
    }

    @Test fun channelLinksAreJustCleaned() =
        assertEquals("https://www.youtube.com/channel/UCabc", ShareLinks.short("https://www.youtube.com/channel/UCabc?si=x"))

    @Test fun clockAndLoop() {
        assertEquals("1:05", ShareLinks.clock(65)); assertEquals("1:01:05", ShareLinks.clock(3665))
        assertEquals("Watch 0:10–0:30: L", ShareLinks.loopMessage("L", 10, 30))
    }
}
