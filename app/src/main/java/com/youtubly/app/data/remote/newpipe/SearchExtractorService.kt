package com.youtubly.app.data.remote.newpipe

import com.youtubly.app.domain.model.Video
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import javax.inject.Inject

class SearchExtractorService @Inject constructor(
    private val streamingService: StreamingService
) {
    fun search(query: String): List<Video> {
        val handler = streamingService.searchQHFactory.fromQuery(query)
        val info = SearchInfo.getInfo(streamingService, handler)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().toVideosWithoutShorts()
    }

    fun suggestions(query: String): List<String> =
        streamingService.suggestionExtractor.suggestionList(query)
}
