package com.youtubly.app.data.remote.newpipe

import com.youtubly.app.domain.model.Video
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Shorts are filtered out everywhere in the app. */
fun StreamInfoItem.isShort(): Boolean = isShortFormContent || url.contains("/shorts/")

fun List<StreamInfoItem>.toVideosWithoutShorts(): List<Video> = filterNot { it.isShort() }.map { it.toVideo() }

fun StreamInfoItem.toVideo(): Video = Video(
    id = extractVideoId(url),
    url = url,
    title = name,
    uploaderName = uploaderName ?: "",
    uploaderUrl = uploaderUrl,
    thumbnailUrl = thumbnails.maxByOrNull { it.height }?.url,
    duration = duration,
    viewCount = viewCount,
    uploadDate = textualUploadDate,
    isLive = streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM,
    uploaderAvatarUrl = runCatching { uploaderAvatars.maxByOrNull { it.height }?.url }.getOrNull()
)

fun extractVideoId(url: String): String {
    val v = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
    if (v != null) return v
    val short = Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
    if (short != null) return short
    val shorts = Regex("/shorts/([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
    return shorts ?: url.substringAfterLast("/")
}
