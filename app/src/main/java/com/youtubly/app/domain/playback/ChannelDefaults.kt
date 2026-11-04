package com.youtubly.app.domain.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What to do whenever a video of one channel opens. null = leave it to the normal rules (network, Settings).
 * Speed is remembered separately (per channel and per video), see UserPreferences.channelSpeeds.
 */
@Serializable
data class ChannelDefaults(
    /** true = open as audio only, false = always with video, null = default. */
    val audioOnly: Boolean? = null,
    /** Fixed quality in pixels of height; null = Auto. */
    val qualityHeight: Int? = null,
    /** Caption language tag ("en", "ar"), "off" = no captions, null = default. */
    val captionLang: String? = null,
    /** Seconds to skip at the start (an intro that repeats in every video). 0 = none. */
    val skipIntroSec: Int = 0,
    /** Seconds to skip at the end (an outro / "subscribe" card): the video ends that much earlier. 0 = none. */
    val skipOutroSec: Int = 0,
    // ---- sound ----
    /** Volume boost in dB (0 = off); null = leave the player's own setting. */
    val volumeBoostDb: Int? = null,
    /** EqPreset name ("SPEECH", "BASS_BOOST"...); null = leave it. */
    val soundPreset: String? = null,
    // ---- feed and downloads ----
    /** Show (true) or hide (false) the Shorts tab on this channel's page; null = follow the Settings switch. */
    val showShortsTab: Boolean? = null,
    /** Videos of this channel whose title contains any of these words are hidden everywhere in the app. */
    val hiddenKeywords: List<String> = emptyList(),
    /** Download this channel's NEW videos by themselves (Wi-Fi, battery not low). */
    val autoDownload: Boolean = false,
    /** true = delete this channel's downloads once watched, false = keep them, null = follow the Downloads switch. */
    val autoDeleteWatched: Boolean? = null
) {
    val isEmpty: Boolean get() = audioOnly == null && qualityHeight == null && captionLang == null && skipIntroSec <= 0 &&
        skipOutroSec <= 0 && volumeBoostDb == null && soundPreset == null && showShortsTab == null &&
        hiddenKeywords.isEmpty() && !autoDownload && autoDeleteWatched == null

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val mapSerializer = MapSerializer(String.serializer(), ChannelDefaults.serializer())

        fun decode(text: String?): Map<String, ChannelDefaults> =
            if (text.isNullOrBlank()) emptyMap() else runCatching { json.decodeFromString(mapSerializer, text) }.getOrDefault(emptyMap())

        fun encode(map: Map<String, ChannelDefaults>): String = json.encodeToString(mapSerializer, map)

        /**
         * Where playback starts. An explicit link time always wins; a resume point past the intro is kept; otherwise the
         * intro is skipped (unless the video is too short for that to make sense).
         */
        fun startPosition(linkStartMs: Long?, resumeAtMs: Long, durationMs: Long, skipIntroSec: Int): Long {
            if (linkStartMs != null) return resumeAtMs
            val intro = skipIntroSec * 1000L
            return when {
                intro <= 0L -> resumeAtMs
                resumeAtMs >= intro -> resumeAtMs
                durationMs in 1..(intro + 30_000L) -> resumeAtMs
                else -> intro
            }
        }

        /** "audio only · 720p · captions en · skips first 20 s" */
        fun summary(d: ChannelDefaults): String = listOfNotNull(
            d.audioOnly?.let { if (it) "audio only" else "with video" },
            d.qualityHeight?.let { "${it}p" },
            d.captionLang?.let { if (it == "off") "captions off" else "captions $it" },
            d.skipIntroSec.takeIf { it > 0 }?.let { "skips first $it s" },
            d.skipOutroSec.takeIf { it > 0 }?.let { "skips last $it s" },
            d.volumeBoostDb?.let { if (it == 0) "no boost" else "+$it dB" },
            d.soundPreset?.let { "sound $it" },
            d.hiddenKeywords.takeIf { it.isNotEmpty() }?.let { "${it.size} hidden word${if (it.size == 1) "" else "s"}" },
            if (d.autoDownload) "auto-download" else null
        ).joinToString(" \u00b7 ").ifEmpty { "nothing yet" }

        /** True when [title] contains one of [keywords] (case, Arabic diacritics and letter variants ignored). */
        fun hidesTitle(keywords: List<String>, title: String): Boolean {
            if (keywords.isEmpty() || title.isBlank()) return false
            val t = com.youtubly.app.core.SearchText.normalize(title)
            return keywords.any { k -> com.youtubly.app.core.SearchText.normalize(k).let { it.isNotBlank() && t.contains(it) } }
        }

        /** Finds a channel's entry whether the map was filled with channel ids or with raw channel URLs. */
        fun <T> lookup(map: Map<String, T>, url: String?): T? {
            if (url.isNullOrBlank()) return null
            return com.youtubly.app.blur.channelKeyOf(url)?.let { map[it] } ?: map[url]
        }

        /** The storage key for a channel. */
        fun keyOf(url: String?): String? = com.youtubly.app.blur.channelKeyOf(url) ?: url?.takeIf { it.isNotBlank() }
    }
}
