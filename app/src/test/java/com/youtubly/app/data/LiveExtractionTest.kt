package com.youtubly.app.data

import com.youtubly.app.data.remote.newpipe.NewPipeDownloaderImpl
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Asks YouTube for a real video through the same extractor the app uses. It only runs when LIVE_TESTS=1 (the
 * "Self-heal YouTube" workflow sets it); everywhere else it is skipped, so normal builds never need the network.
 */
class LiveExtractionTest {
    @Test fun `YouTube still gives a playable stream`() {
        assumeTrue("Live check: set LIVE_TESTS=1 to run it", System.getenv("LIVE_TESTS") == "1")
        NewPipe.init(NewPipeDownloaderImpl(OkHttpClient()))
        // "Me at the zoo": the first video ever uploaded to YouTube, always public and never removed.
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        assertTrue("no title", info.name.isNotBlank())
        val stream = info.audioStreams.firstOrNull() ?: info.videoStreams.firstOrNull()
        assertTrue("no audio or video stream", stream != null)
        assertTrue("stream address is not https", stream!!.content.startsWith("https://"))
    }
}
