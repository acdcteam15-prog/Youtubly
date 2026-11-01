package com.youtubly.app.domain.usecase

import com.youtubly.app.domain.model.ExploreCategory
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.VideoRepository
import javax.inject.Inject

class GetTrendingUseCase @Inject constructor(private val repo: VideoRepository) {
    suspend operator fun invoke(category: ExploreCategory): Result<List<Video>> = repo.getExplore(category)
}
