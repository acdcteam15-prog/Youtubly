package com.youtubly.app.presentation.home

import com.youtubly.app.domain.model.Video
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * The last Home feed, kept on disk so the app shows it instantly at launch while the fresh
 * one loads (and still has something to show when offline).
 */
object HomeFeedCodec {
    const val MAX = 60

    fun encode(videos: List<Video>): String = buildJsonArray {
        videos.take(MAX).forEach { v ->
            add(buildJsonObject {
                put("id", v.id); put("url", v.url); put("title", v.title)
                put("uploaderName", v.uploaderName); put("uploaderUrl", v.uploaderUrl)
                put("thumbnailUrl", v.thumbnailUrl); put("duration", v.duration)
                put("viewCount", v.viewCount); put("uploadDate", v.uploadDate)
                put("isLive", v.isLive); put("uploaderAvatarUrl", v.uploaderAvatarUrl)
            })
        }
    }.toString()

    fun decode(text: String): List<Video> = runCatching {
        Json.parseToJsonElement(text).jsonArray.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L
            val id = s("id") ?: return@mapNotNull null
            Video(
                id = id, url = s("url") ?: "https://www.youtube.com/watch?v=$id", title = s("title").orEmpty(),
                uploaderName = s("uploaderName").orEmpty(), uploaderUrl = s("uploaderUrl"),
                thumbnailUrl = s("thumbnailUrl"), duration = l("duration"), viewCount = l("viewCount"),
                uploadDate = s("uploadDate"), isLive = (o["isLive"] as? JsonPrimitive)?.booleanOrNull ?: false,
                uploaderAvatarUrl = s("uploaderAvatarUrl")
            )
        }
    }.getOrDefault(emptyList())
}

class HomeFeedCache(private val dir: File) {
    private fun file(key: String) = File(dir, "home_feed_" + key.filter { it.isLetterOrDigit() } + ".json")

    fun read(key: String): List<Video> = runCatching { file(key).takeIf { it.exists() }?.readText() }
        .getOrNull()?.let(HomeFeedCodec::decode).orEmpty()

    fun write(key: String, videos: List<Video>) {
        if (videos.isEmpty()) return
        runCatching {
            dir.mkdirs()
            val f = file(key); val tmp = File(f.path + ".tmp")
            tmp.writeText(HomeFeedCodec.encode(videos)); tmp.renameTo(f)
        }
    }
}
