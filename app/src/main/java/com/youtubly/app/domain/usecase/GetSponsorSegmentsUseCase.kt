package com.youtubly.app.domain.usecase

import com.youtubly.app.domain.model.SponsorSegment
import com.youtubly.app.domain.repository.VideoEnrichmentRepository
import javax.inject.Inject

class GetSponsorSegmentsUseCase @Inject constructor(private val repo: VideoEnrichmentRepository) {
    suspend operator fun invoke(videoId: String): Result<List<SponsorSegment>> = repo.getSponsorSegments(videoId)
}
