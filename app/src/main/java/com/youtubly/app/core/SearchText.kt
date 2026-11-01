package com.youtubly.app.core

/**
 * Text normalisation for "contains" searches, so Arabic titles match however the user types them:
 * case-insensitive, diacritics (tashkeel) and tatweel ignored, and the common letter variants
 * (أ إ آ → ا, ى → ي, ة → ه) treated as the same letter.
 */
object SearchText {
    private val diacritics = Regex("[\\u064B-\\u065F\\u0670\\u0640]")

    fun normalize(s: String): String =
        diacritics.replace(s.lowercase(), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
            .trim()

    /** True when every word of [query] occurs somewhere in [text] (both normalised); blank query matches all. */
    fun matches(text: String, query: String): Boolean {
        val words = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val t = normalize(text)
        return words.all { it in t }
    }
}
