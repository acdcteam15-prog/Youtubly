package com.youtubly.app.domain.download

import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadStatus

/** Which finished downloads "auto-delete watched" removes, with a per-channel override of the global switch. */
object AutoDelete {
    /**
     * Completed downloads watched at least [threshold] percent, except [excludeId] (what is playing now).
     * A channel's own setting wins: true = delete its watched downloads, false = keep them, null = follow [globalOn].
     */
    fun targets(
        items: List<DownloadItem>,
        excludeId: String?,
        globalOn: Boolean,
        threshold: Int = 95,
        channelOverride: (uploaderUrl: String?) -> Boolean?
    ): List<DownloadItem> = items.filter {
        it.status == DownloadStatus.COMPLETED && it.watchedPercent >= threshold && it.id != excludeId &&
            (channelOverride(it.uploaderUrl) ?: globalOn)
    }
}
