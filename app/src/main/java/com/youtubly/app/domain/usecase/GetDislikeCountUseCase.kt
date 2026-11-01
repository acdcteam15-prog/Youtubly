package com.youtubly.app.domain.usecase

import com.youtubly.app.domain.repository.VideoEnrichmentRepository
import javax.inject.Inject

class GetDislikeCountUseCase @Inject constructor(private val repo: VideoEnrichmentRepository) {
    suspend operator fun invoke(videoId: String): Result<Long> = repo.getDislikeCount(videoId)
}
