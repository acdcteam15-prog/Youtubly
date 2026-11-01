package com.youtubly.app.data.repository

import com.youtubly.app.core.DispatcherProvider
import com.youtubly.app.core.safeIoCall
import com.youtubly.app.data.remote.newpipe.SearchExtractorService
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.SearchRepository
import javax.inject.Inject

class SearchRepositoryImpl @Inject constructor(
    private val searchExtractorService: SearchExtractorService,
    private val dispatchers: DispatcherProvider
) : SearchRepository {
    override suspend fun search(query: String): Result<List<Video>> =
        safeIoCall(dispatchers) { searchExtractorService.search(query) }

    override suspend fun suggestions(query: String): Result<List<String>> =
        safeIoCall(dispatchers) { searchExtractorService.suggestions(query) }
}
