package com.youtubly.app.core

import java.net.URI
import java.net.URLDecoder

/** Where a YouTube link should take us. */
sealed interface DeepLink {
    /** [url] is a canonical watch URL (Shorts / live / youtu.be are normalised to it). */
    data class Video(val url: String, val startMs: Long? = null) : DeepLink
    data class Channel(val url: String) : DeepLink
    /** [url] is the canonical playlist URL (`/playlist?list=…`). */
    data class Playlist(val url: String) : DeepLink
    /** A video file shared into the app: switch to the Edit tab (the file goes via EditorInbox). */
    data object OpenEditor : DeepLink
    /** A video link *shared* into the app: ask what to do (play, play next, save, download). */
    data class Shared(val url: String, val startMs: Long? = null) : DeepLink
    /** Home-screen shortcut: open a screen by route. */
    data class Open(val route: String) : DeepLink
}

/**
 * Parses YouTube links from browser taps and "Share → Youtubly" text. Pure Kotlin
 * (java.net.URI, no android.net.Uri) so it's unit-testable on the JVM.
 */
object LinkParser {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    /** Public playlists (PL…), channel uploads (UU…), albums (OLAK…); "WL"/"LL" and mixes are not openable. */
    private val PLAYLIST_ID = Regex("^(PL|UU|OLAK5uy_|FL)[A-Za-z0-9_-]{8,}$")
    private val URL_IN_TEXT = Regex("""(https?://)?(www\.|m\.)?(youtube\.com|youtu\.be)/\S+""", RegexOption.IGNORE_CASE)
    private val YT_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com")

    /** Finds the first YouTube link inside shared text ("Check this out: https://youtu.be/…"). */
    fun parseText(text: String?): DeepLink? {
        if (text.isNullOrBlank()) return null
        val found = URL_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?', '"', '\'') ?: return null
        return parse(if (found.startsWith("http", ignoreCase = true)) found else "https://$found")
    }

    fun parse(link: String?): DeepLink? {
        if (link.isNullOrBlank()) return null
        val uri = runCatching { URI(link.trim().replace(" ", "%20")) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map { decode(it) }
        val query = queryParams(uri.rawQuery)
        val start = parseTime(query["t"] ?: query["start"] ?: fragmentTime(uri.rawFragment))

        if (host == "youtu.be" || host == "www.youtu.be") {
            val id = segments.firstOrNull()?.takeIf { VIDEO_ID.matches(it) } ?: return null
            return DeepLink.Video(watchUrl(id), start)
        }
        if (host !in YT_HOSTS) return null

        val first = segments.firstOrNull()
        return when {
            first == "watch" -> query["v"]?.takeIf { VIDEO_ID.matches(it) }?.let { DeepLink.Video(watchUrl(it), start) }
            first == "playlist" -> query["list"]?.takeIf { PLAYLIST_ID.matches(it) }?.let { DeepLink.Playlist(playlistUrl(it)) }
            first in setOf("shorts", "live", "embed", "v") ->
                segments.getOrNull(1)?.takeIf { VIDEO_ID.matches(it) }?.let { DeepLink.Video(watchUrl(it), start) }
            first == "channel" -> segments.getOrNull(1)?.let { DeepLink.Channel("https://www.youtube.com/channel/$it") }
            first == "c" || first == "user" -> segments.getOrNull(1)?.let { DeepLink.Channel("https://www.youtube.com/$first/$it") }
            first != null && first.startsWith("@") && first.length > 1 -> DeepLink.Channel("https://www.youtube.com/$first")
            else -> null
        }
    }

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"

    /** "90", "90s", "1m30s", "1h2m3s", "01:30", "1:02:03" → milliseconds; null if absent/invalid. */
    fun parseTime(raw: String?): Long? {
        val t = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        t.toLongOrNull()?.let { return (it * 1000).takeIf { ms -> ms > 0 } }
        if (':' in t) {
            val parts = t.split(':').map { it.toLongOrNull() ?: return null }
            val secs = parts.fold(0L) { acc, p -> acc * 60 + p }
            return (secs * 1000).takeIf { it > 0 }
        }
        val m = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""").matchEntire(t) ?: return null
        if (m.value.isEmpty()) return null
        val (h, min, s) = m.destructured
        val secs = (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)
        return (secs * 1000).takeIf { it > 0 }
    }

    private fun fragmentTime(fragment: String?): String? =
        fragment?.split('&')?.firstOrNull { it.startsWith("t=") }?.removePrefix("t=")

    private fun queryParams(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else decode(p.substring(0, i)) to decode(p.substring(i + 1))
        }.toMap()

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
