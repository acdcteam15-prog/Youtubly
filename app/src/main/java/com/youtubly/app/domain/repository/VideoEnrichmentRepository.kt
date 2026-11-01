package com.youtubly.app.domain.repository

import com.youtubly.app.domain.model.SponsorSegment

interface VideoEnrichmentRepository {
    suspend fun getSponsorSegments(videoId: String): Result<List<SponsorSegment>>
    suspend fun getDislikeCount(videoId: String): Result<Long>
}
