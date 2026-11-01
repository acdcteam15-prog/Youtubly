package com.youtubly.app.data.repository

import com.youtubly.app.core.DispatcherProvider
import com.youtubly.app.core.safeIoCall
import com.youtubly.app.data.remote.rydb.ReturnYouTubeDislikeApi
import com.youtubly.app.data.remote.sponsorblock.SponsorBlockApi
import com.youtubly.app.domain.model.SponsorSegment
import com.youtubly.app.domain.repository.VideoEnrichmentRepository
import retrofit2.HttpException
import javax.inject.Inject

class VideoEnrichmentRepositoryImpl @Inject constructor(
    private val sponsorBlockApi: SponsorBlockApi,
    private val rydbApi: ReturnYouTubeDislikeApi,
    private val dispatchers: DispatcherProvider
) : VideoEnrichmentRepository {

    override suspend fun getSponsorSegments(videoId: String): Result<List<SponsorSegment>> =
        safeIoCall(dispatchers) {
            try {
                sponsorBlockApi.getSegments(videoId).mapNotNull {
                    if (it.segment.size < 2) null
                    else SponsorSegment(it.segment[0], it.segment[1], it.category)
                }
            } catch (e: HttpException) {
                // SponsorBlock returns 404 when a video has no segments.
                if (e.code() == 404) emptyList() else throw e
            }
        }

    override suspend fun getDislikeCount(videoId: String): Result<Long> =
        safeIoCall(dispatchers) { rydbApi.getVotes(videoId).dislikes }
}
