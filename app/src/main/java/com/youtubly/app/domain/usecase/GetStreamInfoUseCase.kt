package com.youtubly.app.domain.usecase

import com.youtubly.app.domain.model.StreamInfoResult
import com.youtubly.app.domain.repository.VideoRepository
import javax.inject.Inject

class GetStreamInfoUseCase @Inject constructor(private val repo: VideoRepository) {
    suspend operator fun invoke(videoUrl: String): Result<StreamInfoResult> = repo.getStreamInfo(videoUrl)
}
