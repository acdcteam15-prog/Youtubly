package com.youtubly.app.domain.playback

import com.youtubly.app.domain.model.Video

/**
 * Hides videos of channels that have "hidden words" set. Lists from YouTube (Home, search, channel pages, related)
 * all pass through [allows], so one place keeps the rule everywhere. The rules are fed from the settings by the app.
 */
object ChannelRulesFilter {
    @Volatile private var keywordsByChannel: Map<String, List<String>> = emptyMap()

    fun update(rules: Map<String, ChannelDefaults>) {
        keywordsByChannel = rules.filterValues { it.hiddenKeywords.isNotEmpty() }.mapValues { it.value.hiddenKeywords }
    }

    fun allows(video: Video): Boolean {
        val rules = keywordsByChannel
        if (rules.isEmpty()) return true
        val words = ChannelDefaults.lookup(rules, video.uploaderUrl) ?: return true
        return !ChannelDefaults.hidesTitle(words, video.title)
    }
}
