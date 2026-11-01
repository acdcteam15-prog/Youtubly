package com.youtubly.app.domain.library

import com.youtubly.app.domain.model.Video
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** User-made groups of channels ("Lectures", "News"): name → channel URLs. Pure functions over an immutable map. */
object ChannelGroups {
    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    fun decode(json: String?): Map<String, List<String>> =
        if (json.isNullOrBlank()) emptyMap() else runCatching { Json.decodeFromString(serializer, json) }.getOrDefault(emptyMap())

    fun encode(groups: Map<String, List<String>>): String = Json.encodeToString(serializer, groups)

    fun add(groups: Map<String, List<String>>, name: String, channelUrl: String): Map<String, List<String>> {
        val n = name.trim().take(40)
        if (n.isEmpty() || channelUrl.isBlank()) return groups
        val result = LinkedHashMap(groups)
        val members = result[n].orEmpty()
        if (channelUrl !in members) result[n] = members + channelUrl
        else result.putIfAbsent(n, members)
        return result
    }

    /** A new, empty group (ignored when the name is blank or already used). */
    fun create(groups: Map<String, List<String>>, name: String): Map<String, List<String>> {
        val n = name.trim().take(40)
        return if (n.isEmpty() || n in groups) groups else groups + (n to emptyList())
    }

    fun remove(groups: Map<String, List<String>>, name: String, channelUrl: String): Map<String, List<String>> =
        groups.mapValues { (g, urls) -> if (g == name) urls.filterNot { it == channelUrl } else urls }

    fun deleteGroup(groups: Map<String, List<String>>, name: String): Map<String, List<String>> = groups.filterKeys { it != name }

    fun rename(groups: Map<String, List<String>>, old: String, new: String): Map<String, List<String>> {
        val n = new.trim().take(40)
        if (n.isEmpty() || n == old || old !in groups || n in groups) return groups
        return LinkedHashMap<String, List<String>>().also { out -> groups.forEach { (k, v) -> out[if (k == old) n else k] = v } }
    }

    fun groupsOf(groups: Map<String, List<String>>, channelUrl: String): List<String> =
        groups.filterValues { channelUrl in it }.keys.toList()
}

/** Turns YouTube's "3 days ago" into a number so videos from many channels can be put in order. */
object UploadAge {
    private val RELATIVE = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)
    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

    /** Age in minutes (smaller = newer). Unknown text sorts last. [nowMs] only matters for ISO dates. */
    fun minutes(text: String?, nowMs: Long = System.currentTimeMillis()): Long {
        if (text.isNullOrBlank()) return Long.MAX_VALUE
        RELATIVE.find(text)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return Long.MAX_VALUE
            val unit = when (m.groupValues[2].lowercase()) {
                "second" -> 0L; "minute" -> 1L; "hour" -> 60L; "day" -> 1_440L
                "week" -> 10_080L; "month" -> 43_200L; else -> 525_600L
            }
            return if (unit == 0L) 0L else n * unit
        }
        ISO.find(text)?.let { m ->
            val (y, mo, d) = m.destructured
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear(); set(y.toInt(), mo.toInt() - 1, d.toInt())
            }
            return ((nowMs - cal.timeInMillis) / 60_000L).coerceAtLeast(0L)
        }
        return Long.MAX_VALUE
    }
}

/** The merged "what's new" feed of a channel group. */
object GroupFeed {
    /** Newest first, one entry per video, at most [limit]. Equal ages keep the order they came in. */
    fun merge(perChannel: List<List<Video>>, limit: Int = 60, nowMs: Long = System.currentTimeMillis()): List<Video> =
        perChannel.flatten().distinctBy { it.id }.sortedBy { UploadAge.minutes(it.uploadDate, nowMs) }.take(limit)
}
