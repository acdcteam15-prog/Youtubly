package com.youtubly.app.domain.usecase

import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.SearchRepository
import javax.inject.Inject

class SearchVideosUseCase @Inject constructor(private val repo: SearchRepository) {
    suspend operator fun invoke(query: String): Result<List<Video>> = repo.search(query)
}
