package com.youtubly.app.data.remote.innertube

import com.youtubly.app.domain.model.SubscribedChannel
import com.youtubly.app.domain.model.UserProfile
import com.youtubly.app.domain.model.Video
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Tolerant parser for InnerTube responses. Instead of hard-coding exact JSON paths
 * (which YouTube reshuffles every few months) it walks the whole tree and picks up
 * every video card it recognises — old `videoRenderer` and new `lockupViewModel`
 * formats alike — while skipping every Shorts container.
 */
object InnertubeParser {

    /** Subtrees that only ever contain Shorts, ads, or non-video promos. */
    private val SKIP_KEYS = setOf(
        "reelShelfRenderer", "reelItemRenderer", "shortsLockupViewModel",
        "richSectionRenderer", "adSlotRenderer", "promotedSparklesWebRenderer",
        "statementBannerRenderer", "brandVideoShelfRenderer", "movingThumbnailRenderer",
        "frameworkUpdates"
    )
    private val VIDEO_RENDERERS = setOf(
        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer"
    )

    data class Page(val videos: List<Video>, val continuation: String?)

    fun parseFeed(root: JsonObject): Page {
        val videos = LinkedHashMap<String, Video>()
        val tokens = mutableListOf<String>()
        walk(root, videos, tokens)
        return Page(videos.values.toList(), tokens.lastOrNull())
    }

    private fun walk(el: JsonElement, out: MutableMap<String, Video>, tokens: MutableList<String>) {
        when (el) {
            is JsonObject -> for ((k, v) in el) {
                when (k) {
                    in SKIP_KEYS -> Unit
                    in VIDEO_RENDERERS -> (v as? JsonObject)?.let(::parseVideoRenderer)?.let { out.putIfAbsent(it.id, it) }
                    "lockupViewModel" -> (v as? JsonObject)?.let(::parseLockup)?.let { out.putIfAbsent(it.id, it) }
                    "continuationCommand" -> (v as? JsonObject)?.str("token")?.let(tokens::add)
                    else -> walk(v, out, tokens)
                }
            }
            is JsonArray -> el.forEach { walk(it, out, tokens) }
            else -> Unit
        }
    }

    // ---------- classic renderers ----------
    private fun parseVideoRenderer(o: JsonObject): Video? {
        val id = o.str("videoId") ?: return null
        // A videoRenderer that opens the Shorts player is a Short in disguise.
        if (o.obj("navigationEndpoint")?.get("reelWatchEndpoint") != null) return null
        val title = text(o["title"]) ?: return null
        val owner = (o["ownerText"] ?: o["shortBylineText"] ?: o["longBylineText"])
        val lengthText = text(o["lengthText"]) ?: o.str("lengthSeconds")?.let { formatSecs(it.toLongOrNull() ?: 0) }
            ?: findTimeStatus(o)
        val viewsText = text(o["viewCountText"]) ?: text(o["shortViewCountText"])
        val isLive = viewsText?.contains("watching", true) == true ||
            o.toString().contains("BADGE_STYLE_TYPE_LIVE_NOW")
        val (notInterested, dontRecommend) = parseFeedbackTokens(o.obj("menu"))
        return Video(
            id = id,
            url = "https://www.youtube.com/watch?v=$id",
            title = title,
            uploaderName = text(owner).orEmpty(),
            uploaderUrl = findString(owner, "browseId")?.takeIf { it.startsWith("UC") }?.let(::channelUrl),
            thumbnailUrl = bestThumb(o.obj("thumbnail")) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            duration = parseDuration(lengthText),
            viewCount = parseCount(viewsText),
            uploadDate = text(o["publishedTimeText"]),
            isLive = isLive,
            uploaderAvatarUrl = findObject(o, "channelThumbnailSupportedRenderers")?.let { findObject(it, "thumbnail") }?.let(::bestThumb)
                ?: bestThumb(o.obj("channelThumbnail")),
            notInterestedToken = notInterested,
            dontRecommendChannelToken = dontRecommend
        )
    }

    /**
     * A video's overflow menu carries two "feedback" items whose click endpoint
     * holds an opaque token — replaying it via /youtubei/v1/feedback removes the
     * video (or the whole channel) from future Home/Subscriptions feeds.
     */
    private fun parseFeedbackTokens(menu: JsonObject?): Pair<String?, String?> {
        if (menu == null) return null to null
        var notInterested: String? = null
        var dontRecommend: String? = null

        fun classify(label: String, icon: String, token: String) {
            val l = label.replace('\u2019', '\'')
            when {
                icon == "NOT_INTERESTED" || l.contains("Not interested", true) ->
                    if (notInterested == null) notInterested = token
                icon == "REMOVE" || l.contains("Don't recommend", true) || l.contains("recommend channel", true) ->
                    if (dontRecommend == null) dontRecommend = token
            }
        }

        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> {
                    // Classic menu: menuServiceItemRenderer { text, icon.iconType, serviceEndpoint.feedbackEndpoint }
                    (el["menuServiceItemRenderer"] as? JsonObject)?.let { item ->
                        val token = findString(item.obj("serviceEndpoint")?.let { findObject(it, "feedbackEndpoint") }, "feedbackToken")
                        if (token != null) classify(text(item["text"]).orEmpty(), item.obj("icon")?.str("iconType").orEmpty(), token)
                    }
                    // 2025+ lockup sheet: listItemViewModel { title.content, leadingImage…imageName, rendererContext…feedbackEndpoint }
                    (el["listItemViewModel"] as? JsonObject)?.let { item ->
                        val endpoint = findObject(item, "feedbackEndpoint")
                        val token = findString(endpoint, "feedbackToken")
                        if (token != null) {
                            val label = item.obj("title")?.str("content").orEmpty()
                            val icon = findString(item.obj("leadingImage"), "imageName").orEmpty()
                            classify(label, icon, token)
                        }
                    }
                    for (v in el.values) walk(v)
                }
                is JsonArray -> el.forEach(::walk)
                else -> Unit
            }
        }
        walk(menu)
        return notInterested to dontRecommend
    }

    // ---------- 2025+ lockup format ----------
    private fun parseLockup(o: JsonObject): Video? {
        if (o.str("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        val id = o.str("contentId") ?: return null
        val md = o.obj("metadata")?.obj("lockupMetadataViewModel") ?: return null
        val title = md.obj("title")?.str("content") ?: return null
        val rows = md.obj("metadata")?.obj("contentMetadataViewModel")?.arr("metadataRows").orEmpty()
        val rowTexts = rows.map { row ->
            (row as? JsonObject)?.arr("metadataParts").orEmpty()
                .mapNotNull { (it as? JsonObject)?.obj("text")?.str("content") }
        }
        val channel = rowTexts.getOrNull(0)?.firstOrNull().orEmpty()
        val rest = rowTexts.drop(1).flatten()
        val views = rest.firstOrNull { it.contains("view", true) || it.contains("watching", true) }
        val date = rest.firstOrNull { it.contains("ago", true) || it.startsWith("Streamed", true) || it.startsWith("Premier", true) }
        val badge = findString(o.obj("contentImage"), "text")
        val isLive = badge.equals("LIVE", true) || views?.contains("watching", true) == true
        val channelId = findString(md, "browseId")?.takeIf { it.startsWith("UC") }
        val thumb = o.obj("contentImage")?.let { findArray(it, "sources") }
            ?.mapNotNull { (it as? JsonObject)?.str("url") }?.lastOrNull()
        val (notInterested, dontRecommend) = parseFeedbackTokens(o)
        return Video(
            id = id,
            url = "https://www.youtube.com/watch?v=$id",
            title = title,
            uploaderName = channel,
            uploaderUrl = channelId?.let(::channelUrl),
            thumbnailUrl = thumb ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            duration = if (isLive) 0 else parseDuration(badge),
            viewCount = parseCount(views),
            uploadDate = date,
            isLive = isLive,
            uploaderAvatarUrl = findObject(md, "avatarViewModel")?.let { findArray(it, "sources") }
                ?.mapNotNull { (it as? JsonObject)?.str("url") }?.lastOrNull(),
            notInterestedToken = notInterested,
            dontRecommendChannelToken = dontRecommend
        )
    }

    // ---------- account ----------
    fun parseProfile(root: JsonObject): UserProfile? {
        val header = findObject(root, "activeAccountHeaderRenderer") ?: return null
        val name = text(header["accountName"]) ?: return null
        return UserProfile(
            channelId = findString(root, "browseId")?.takeIf { it.startsWith("UC") }.orEmpty(),
            name = name,
            handle = text(header["channelHandle"]),
            avatarUrl = bestThumb(header.obj("accountPhoto")),
            subscriberCount = null
        )
    }

    fun parseChannels(root: JsonObject): Pair<List<SubscribedChannel>, String?> {
        val out = LinkedHashMap<String, SubscribedChannel>()
        val tokens = mutableListOf<String>()
        fun walk(el: JsonElement) {
            when (el) {
                is JsonObject -> for ((k, v) in el) {
                    if (k == "channelRenderer" || k == "gridChannelRenderer") {
                        val o = v as? JsonObject ?: continue
                        val id = o.str("channelId") ?: continue
                        out.putIfAbsent(id, SubscribedChannel(id, text(o["title"]).orEmpty(), bestThumb(o.obj("thumbnail"))))
                    } else if (k == "continuationCommand") {
                        (v as? JsonObject)?.str("token")?.let(tokens::add)
                    } else walk(v)
                }
                is JsonArray -> el.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out.values.toList() to tokens.lastOrNull()
    }

    /** Like / subscribe state from the /next (watch page) response. */
    fun parseLikeStatus(root: JsonObject): Boolean =
        findString(root, "likeStatus") == "LIKE"

    fun parseSubscribed(root: JsonObject): Boolean? {
        findObject(root, "subscribeButtonRenderer")?.get("subscribed")?.let { (it as? JsonPrimitive)?.booleanOrNull }?.let { return it }
        return findObject(root, "subscriptionStateEntity")?.get("subscribed")?.let { (it as? JsonPrimitive)?.booleanOrNull }
    }

    fun parseChannelId(root: JsonObject): String? =
        findObject(root, "subscribeButtonRenderer")?.str("channelId")
            ?: findObject(root, "videoOwnerRenderer")?.let { findString(it, "browseId") }

    // ---------- helpers ----------
    fun channelUrl(id: String) = "https://www.youtube.com/channel/$id"

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray

    private fun text(el: JsonElement?): String? {
        val o = el as? JsonObject ?: return null
        o.str("simpleText")?.let { return it }
        o.str("content")?.let { return it }
        return o.arr("runs")?.joinToString("") { (it as? JsonObject)?.str("text").orEmpty() }?.ifBlank { null }
    }

    private fun bestThumb(o: JsonObject?): String? {
        val url = o?.arr("thumbnails")?.mapNotNull { (it as? JsonObject)?.str("url") }?.lastOrNull() ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun findTimeStatus(o: JsonObject): String? =
        findObject(o, "thumbnailOverlayTimeStatusRenderer")?.let { text(it["text"]) }

    private fun findString(el: JsonElement?, key: String): String? {
        when (el) {
            is JsonObject -> {
                (el[key] as? JsonPrimitive)?.contentOrNull?.let { return it }
                for (v in el.values) findString(v, key)?.let { return it }
            }
            is JsonArray -> for (v in el) findString(v, key)?.let { return it }
            else -> Unit
        }
        return null
    }

    private fun findObject(el: JsonElement?, key: String): JsonObject? {
        when (el) {
            is JsonObject -> {
                (el[key] as? JsonObject)?.let { return it }
                for (v in el.values) findObject(v, key)?.let { return it }
            }
            is JsonArray -> for (v in el) findObject(v, key)?.let { return it }
            else -> Unit
        }
        return null
    }

    private fun findArray(el: JsonElement?, key: String): JsonArray? {
        when (el) {
            is JsonObject -> {
                (el[key] as? JsonArray)?.let { return it }
                for (v in el.values) findArray(v, key)?.let { return it }
            }
            is JsonArray -> for (v in el) findArray(v, key)?.let { return it }
            else -> Unit
        }
        return null
    }

    fun parseDuration(text: String?): Long {
        if (text.isNullOrBlank()) return 0
        val parts = text.trim().split(':').map { it.trim().toLongOrNull() ?: return 0 }
        return parts.fold(0L) { acc, p -> acc * 60 + p }
    }

    private fun formatSecs(s: Long): String = "%d:%02d".format(s / 60, s % 60)

    /** "1.2M views" → 1200000, "1,234 views" → 1234, "No views" → 0, unknown → -1 */
    fun parseCount(text: String?): Long {
        if (text.isNullOrBlank()) return -1
        if (text.startsWith("No ", true)) return 0
        val m = Regex("""([\d.,]+)\s*([KMB])?""", RegexOption.IGNORE_CASE).find(text) ?: return -1
        val num = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return -1
        val mult = when (m.groupValues[2].uppercase()) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
        return (num * mult).toLong()
    }
}
