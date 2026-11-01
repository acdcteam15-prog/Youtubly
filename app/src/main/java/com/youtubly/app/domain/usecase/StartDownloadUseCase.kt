package com.youtubly.app.domain.usecase

import com.youtubly.app.domain.model.DownloadOption
import com.youtubly.app.domain.repository.DownloadRepository
import javax.inject.Inject

class StartDownloadUseCase @Inject constructor(private val repo: DownloadRepository) {
    suspend operator fun invoke(videoId: String, videoUrl: String, title: String, thumbnailUrl: String?, option: DownloadOption, uploaderUrl: String? = null): String =
        repo.enqueueDownload(videoId, videoUrl, title, thumbnailUrl, option, uploaderUrl)
}
