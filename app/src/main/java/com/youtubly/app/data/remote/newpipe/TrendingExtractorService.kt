package com.youtubly.app.data.remote.newpipe

import com.youtubly.app.domain.model.Video
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject

/** Loads YouTube chart "kiosks" (Music, Gaming, Movies, Podcasts, Live). */
class TrendingExtractorService @Inject constructor(
    private val streamingService: StreamingService
) {
    fun getKiosk(kioskId: String): List<Video> {
        val kioskList = streamingService.kioskList
        val url = kioskList.getListLinkHandlerFactoryByType(kioskId).fromId(kioskId).url
        val info = KioskInfo.getInfo(streamingService, url)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().toVideosWithoutShorts()
    }
}
