package com.youtubly.app.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.youtubly.app.core.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.userPrefsDataStore by preferencesDataStore(name = Constants.DATASTORE_PREFS_NAME)

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyAutoSkipSponsors = booleanPreferencesKey("auto_skip_sponsors")
    private val keyDeArrow = booleanPreferencesKey("dearrow")
    private val keyPip = booleanPreferencesKey("auto_pip")
    private val keySpeed = floatPreferencesKey("playback_speed")

    val autoSkipSponsors: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyAutoSkipSponsors] ?: true }
    val deArrow: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyDeArrow] ?: true }
    val autoPip: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyPip] ?: true }
    val playbackSpeed: Flow<Float> = context.userPrefsDataStore.data.map { it[keySpeed] ?: 1f }

    private val keyHiddenVideos = stringSetPreferencesKey("hidden_video_ids")
    private val keyBlockedChannels = stringSetPreferencesKey("blocked_channels")

    /** Videos marked "Not interested" — hidden from every list in the app. */
    val hiddenVideos: Flow<Set<String>> = context.userPrefsDataStore.data.map { it[keyHiddenVideos] ?: emptySet() }
    /** Channels marked "Don't recommend channel" (channel URL and "name:<name>" keys). */
    val blockedChannels: Flow<Set<String>> = context.userPrefsDataStore.data.map { it[keyBlockedChannels] ?: emptySet() }

    private val keySubsSeen = stringSetPreferencesKey("subs_seen_ids")
    /** Video ids already seen on the Subscriptions feed — for the "new uploads" badge. */
    val subsSeenIds: Flow<Set<String>> = context.userPrefsDataStore.data.map { it[keySubsSeen] ?: emptySet() }
    suspend fun setSubsSeenIds(v: Set<String>) { context.userPrefsDataStore.edit { it[keySubsSeen] = v } }

    suspend fun setHiddenVideos(v: Set<String>) { context.userPrefsDataStore.edit { it[keyHiddenVideos] = v } }
    suspend fun setBlockedChannels(v: Set<String>) { context.userPrefsDataStore.edit { it[keyBlockedChannels] = v } }

    suspend fun setAutoSkipSponsors(v: Boolean) { context.userPrefsDataStore.edit { it[keyAutoSkipSponsors] = v } }
    suspend fun setDeArrow(v: Boolean) { context.userPrefsDataStore.edit { it[keyDeArrow] = v } }
    suspend fun setAutoPip(v: Boolean) { context.userPrefsDataStore.edit { it[keyPip] = v } }
    suspend fun setPlaybackSpeed(v: Float) { context.userPrefsDataStore.edit { it[keySpeed] = v } }

    // ---------------- Phase 1: playback power features ----------------
    private val keyBackgroundAudio = booleanPreferencesKey("background_audio")
    private val keyWifiQuality = intPreferencesKey("wifi_quality")
    private val keyMobileQuality = intPreferencesKey("mobile_quality")
    private val keyDataSaver = booleanPreferencesKey("data_saver")
    private val keyDataSaverAudioOnly = booleanPreferencesKey("data_saver_audio_only")
    private val keySkipSilence = booleanPreferencesKey("skip_silence")
    private val keyChannelSpeeds = stringPreferencesKey("channel_speeds_json")

    /** "Keep audio when screen is off": on = video track is dropped and audio continues when
     *  the app is backgrounded or the screen locks; off = playback pauses instead. */
    val backgroundAudio: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyBackgroundAudio] ?: true }
    /** Max video height on Wi-Fi / unmetered networks. 0 = Auto (no cap). */
    val wifiQuality: Flow<Int> = context.userPrefsDataStore.data.map { it[keyWifiQuality] ?: 0 }
    /** Max video height on mobile data / metered networks. 0 = Auto (no cap). */
    val mobileQuality: Flow<Int> = context.userPrefsDataStore.data.map { it[keyMobileQuality] ?: 480 }
    /** On mobile data: small thumbnails, 360p cap, no DeArrow thumbnails. */
    val dataSaver: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyDataSaver] ?: false }
    /** With data saver active, start every video in audio-only mode. */
    val dataSaverAudioOnly: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyDataSaverAudioOnly] ?: false }
    val skipSilence: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keySkipSilence] ?: false }
    /** Channel URL → remembered playback speed. */
    val channelSpeeds: Flow<Map<String, Float>> = context.userPrefsDataStore.data.map { decodeSpeeds(it[keyChannelSpeeds]) }

    suspend fun setBackgroundAudio(v: Boolean) { context.userPrefsDataStore.edit { it[keyBackgroundAudio] = v } }
    suspend fun setWifiQuality(v: Int) { context.userPrefsDataStore.edit { it[keyWifiQuality] = v } }
    suspend fun setMobileQuality(v: Int) { context.userPrefsDataStore.edit { it[keyMobileQuality] = v } }
    suspend fun setDataSaver(v: Boolean) { context.userPrefsDataStore.edit { it[keyDataSaver] = v } }
    suspend fun setDataSaverAudioOnly(v: Boolean) { context.userPrefsDataStore.edit { it[keyDataSaverAudioOnly] = v } }
    suspend fun setSkipSilence(v: Boolean) { context.userPrefsDataStore.edit { it[keySkipSilence] = v } }

    /** Remember [speed] for [channelUrl]; null removes the entry (falls back to the global speed). */
    suspend fun setChannelSpeed(channelUrl: String, speed: Float?) {
        context.userPrefsDataStore.edit { prefs ->
            val map = decodeSpeeds(prefs[keyChannelSpeeds]).toMutableMap()
            if (speed == null) map.remove(channelUrl) else map[channelUrl] = speed
            prefs[keyChannelSpeeds] = encodeSpeeds(map)
        }
    }

    private fun decodeSpeeds(json: String?): Map<String, Float> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = org.json.JSONObject(json)
            o.keys().asSequence().associateWith { o.getDouble(it).toFloat() }
        }.getOrDefault(emptyMap())
    }

    private fun encodeSpeeds(map: Map<String, Float>): String {
        val o = org.json.JSONObject()
        map.forEach { (k, v) -> o.put(k, v.toDouble()) }
        return o.toString()
    }

    // ---------------- Phase 3: appearance ----------------
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyAccent = intPreferencesKey("accent_color")

    /** "SYSTEM", "LIGHT", "DARK" or "BLACK" (see presentation.theme.ThemeMode). */
    val themeMode: Flow<String> = context.userPrefsDataStore.data.map { it[keyThemeMode] ?: "SYSTEM" }
    /** Index into presentation.theme.Accents. */
    val accent: Flow<Int> = context.userPrefsDataStore.data.map { it[keyAccent] ?: 0 }
    suspend fun setThemeMode(v: String) { context.userPrefsDataStore.edit { it[keyThemeMode] = v } }
    suspend fun setAccent(v: Int) { context.userPrefsDataStore.edit { it[keyAccent] = v } }

    private val keyPinnedChannels = stringSetPreferencesKey("pinned_channel_ids")
    /** Channel ids pinned to the top of the Subscriptions screen (local only). */
    val pinnedChannels: Flow<Set<String>> = context.userPrefsDataStore.data.map { it[keyPinnedChannels] ?: emptySet() }
    suspend fun setChannelPinned(channelId: String, pinned: Boolean) {
        context.userPrefsDataStore.edit {
            val cur = it[keyPinnedChannels] ?: emptySet()
            it[keyPinnedChannels] = if (pinned) cur + channelId else cur - channelId
        }
    }

    private val keyAutoplayNext = booleanPreferencesKey("autoplay_next")
    /** Play the next item of a playlist/queue when a video ends. */
    val autoplayNext: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyAutoplayNext] ?: true }
    suspend fun setAutoplayNext(v: Boolean) { context.userPrefsDataStore.edit { it[keyAutoplayNext] = v } }

    // ---- Bedtime mode: dark + warm theme and a volume cap during a nightly window ----
    private val keyBedtimeOn = booleanPreferencesKey("bedtime_on")
    private val keyBedtimeStart = intPreferencesKey("bedtime_start_min")   // minutes after midnight
    private val keyBedtimeEnd = intPreferencesKey("bedtime_end_min")
    private val keyBedtimeVolume = intPreferencesKey("bedtime_volume_pct")
    val bedtimeOn: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyBedtimeOn] ?: false }
    val bedtimeStartMin: Flow<Int> = context.userPrefsDataStore.data.map { it[keyBedtimeStart] ?: (22 * 60) }
    val bedtimeEndMin: Flow<Int> = context.userPrefsDataStore.data.map { it[keyBedtimeEnd] ?: (7 * 60) }
    /** Player volume while bedtime is active, 20–100 %. */
    val bedtimeVolumePct: Flow<Int> = context.userPrefsDataStore.data.map { it[keyBedtimeVolume] ?: 60 }
    suspend fun setBedtimeOn(v: Boolean) { context.userPrefsDataStore.edit { it[keyBedtimeOn] = v } }
    suspend fun setBedtimeStartMin(v: Int) { context.userPrefsDataStore.edit { it[keyBedtimeStart] = v.coerceIn(0, 1439) } }
    suspend fun setBedtimeEndMin(v: Int) { context.userPrefsDataStore.edit { it[keyBedtimeEnd] = v.coerceIn(0, 1439) } }
    suspend fun setBedtimeVolumePct(v: Int) { context.userPrefsDataStore.edit { it[keyBedtimeVolume] = v.coerceIn(20, 100) } }

    private val keyTintedSurfaces = booleanPreferencesKey("tinted_surfaces")
    /** Tint backgrounds and cards toward the accent / palette colour. */
    val tintedSurfaces: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyTintedSurfaces] ?: false }
    suspend fun setTintedSurfaces(v: Boolean) { context.userPrefsDataStore.edit { it[keyTintedSurfaces] = v } }

    // ---------------- Phase 5: polish ----------------
    private val keyDynamicColor = booleanPreferencesKey("dynamic_color")
    private val keySeekStep = intPreferencesKey("seek_step_seconds")

    /** Material You: take the colour scheme from the wallpaper (Android 12+ only). */
    val dynamicColor: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyDynamicColor] ?: false }
    /** Double-tap seek step in seconds: 5, 10, 15 or 30. */
    val seekStepSeconds: Flow<Int> = context.userPrefsDataStore.data.map { it[keySeekStep] ?: 10 }
    suspend fun setDynamicColor(v: Boolean) { context.userPrefsDataStore.edit { it[keyDynamicColor] = v } }
    suspend fun setSeekStepSeconds(v: Int) { context.userPrefsDataStore.edit { it[keySeekStep] = v } }

    // ---------------- Swipe actions on video cards ----------------
    private val keySwipeRight = booleanPreferencesKey("swipe_right_not_interested")
    private val keySwipeLeft = booleanPreferencesKey("swipe_left_dont_recommend")
    private val keySwipeDistance = intPreferencesKey("swipe_distance_pct")

    val swipeActions: Flow<com.youtubly.app.presentation.components.SwipeSettings> = context.userPrefsDataStore.data.map {
        com.youtubly.app.presentation.components.SwipeSettings(
            right = it[keySwipeRight] ?: true,
            left = it[keySwipeLeft] ?: true,
            distancePct = it[keySwipeDistance] ?: 55,
        )
    }
    suspend fun setSwipeRight(v: Boolean) { context.userPrefsDataStore.edit { it[keySwipeRight] = v } }
    suspend fun setSwipeLeft(v: Boolean) { context.userPrefsDataStore.edit { it[keySwipeLeft] = v } }
    suspend fun setSwipeDistance(pct: Int) { context.userPrefsDataStore.edit { it[keySwipeDistance] = pct.coerceIn(30, 85) } }

    // ---------------- Phase 8: adaptive ----------------
    private val keyRotateFullscreen = booleanPreferencesKey("rotate_to_fullscreen")
    /** Phones: rotating to landscape while the player is open enters fullscreen. */
    val rotateToFullscreen: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyRotateFullscreen] ?: false }
    suspend fun setRotateToFullscreen(v: Boolean) { context.userPrefsDataStore.edit { it[keyRotateFullscreen] = v } }

    // ---------------- Phase 2: library & downloads ----------------
    private val keyPauseHistory = booleanPreferencesKey("pause_history")
    private val keyAutoDeleteWatched = booleanPreferencesKey("auto_delete_watched_downloads")
    private val keyHiddenVideoRecords = stringPreferencesKey("hidden_video_records_json")
    private val keyBlockedChannelRecords = stringPreferencesKey("blocked_channel_records_json")

    /** While on, nothing new is added to watch history (resume positions of existing entries still update). */
    val pauseHistory: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyPauseHistory] ?: false }
    /** Delete a finished download once it's ≥95 % watched. */
    val autoDeleteWatched: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyAutoDeleteWatched] ?: false }
    suspend fun setPauseHistory(v: Boolean) { context.userPrefsDataStore.edit { it[keyPauseHistory] = v } }
    suspend fun setAutoDeleteWatched(v: Boolean) { context.userPrefsDataStore.edit { it[keyAutoDeleteWatched] = v } }

    /** Display details for hidden videos, keyed by video id. The id set above stays the source of truth. */
    val hiddenVideoRecords: Flow<Map<String, HiddenRecord>> =
        context.userPrefsDataStore.data.map { HiddenRecord.decodeMap(it[keyHiddenVideoRecords]) }
    /** Display details for blocked channels, keyed by the channel's primary key (URL, else "name:<n>"). */
    val blockedChannelRecords: Flow<Map<String, HiddenRecord>> =
        context.userPrefsDataStore.data.map { HiddenRecord.decodeMap(it[keyBlockedChannelRecords]) }

    suspend fun setHiddenVideoRecords(v: Map<String, HiddenRecord>) {
        context.userPrefsDataStore.edit { it[keyHiddenVideoRecords] = HiddenRecord.encodeMap(v) }
    }
    suspend fun setBlockedChannelRecords(v: Map<String, HiddenRecord>) {
        context.userPrefsDataStore.edit { it[keyBlockedChannelRecords] = HiddenRecord.encodeMap(v) }
    }

    // ---------------- Editor: remember the last export settings ----------------
    private val keyEditorHeight = intPreferencesKey("editor_out_height")   // 0 = original
    private val keyEditorKbps = intPreferencesKey("editor_out_kbps")       // 0 = original
    val editorMaxHeight: Flow<Int?> = context.userPrefsDataStore.data.map { it[keyEditorHeight]?.takeIf { v -> v > 0 } }
    val editorBitrateKbps: Flow<Int?> = context.userPrefsDataStore.data.map { it[keyEditorKbps]?.takeIf { v -> v > 0 } }
    suspend fun setEditorOutput(maxHeight: Int?, kbps: Int?) {
        context.userPrefsDataStore.edit { it[keyEditorHeight] = maxHeight ?: 0; it[keyEditorKbps] = kbps ?: 0 }
    }

    // ---------------- SponsorBlock: which categories to auto-skip ----------------
    private val keySbCategories = stringSetPreferencesKey("sb_skip_categories")
    val sponsorSkipCategories: Flow<Set<String>> =
        context.userPrefsDataStore.data.map { it[keySbCategories] ?: SponsorCategories.DEFAULT_SKIP }
    suspend fun setSponsorSkipCategories(v: Set<String>) { context.userPrefsDataStore.edit { it[keySbCategories] = v } }

    /** Raw store, for Backup & restore only. */
    internal val rawStore get() = context.userPrefsDataStore

    // ---------------- download rules (Wi-Fi only / battery / overnight window) ----------------
    private val keyDlWifiOnly = booleanPreferencesKey("dl_wifi_only")
    private val keyDlMinBattery = intPreferencesKey("dl_min_battery")
    private val keyDlSchedule = booleanPreferencesKey("dl_schedule")
    private val keyDlStartHour = intPreferencesKey("dl_start_hour")
    private val keyDlEndHour = intPreferencesKey("dl_end_hour")

    val downloadRules: Flow<com.youtubly.app.domain.download.DownloadRules> = context.userPrefsDataStore.data.map {
        com.youtubly.app.domain.download.DownloadRules(
            wifiOnly = it[keyDlWifiOnly] ?: false,
            minBatteryPercent = it[keyDlMinBattery] ?: 0,
            scheduleEnabled = it[keyDlSchedule] ?: false,
            startHour = it[keyDlStartHour] ?: 1,
            endHour = it[keyDlEndHour] ?: 7
        )
    }
    suspend fun setDownloadRules(r: com.youtubly.app.domain.download.DownloadRules) {
        context.userPrefsDataStore.edit {
            it[keyDlWifiOnly] = r.wifiOnly
            it[keyDlMinBattery] = r.minBatteryPercent
            it[keyDlSchedule] = r.scheduleEnabled
            it[keyDlStartHour] = r.startHour.coerceIn(0, 23)
            it[keyDlEndHour] = r.endHour.coerceIn(0, 23)
        }
    }

    // ---------------- Saved list sort ----------------
    private val keySavedSort = stringPreferencesKey("saved_sort")
    val savedSort: Flow<String> = context.userPrefsDataStore.data.map { it[keySavedSort] ?: "NEWEST" }
    suspend fun setSavedSort(v: String) { context.userPrefsDataStore.edit { it[keySavedSort] = v } }

    // ---------------- channel pages ----------------
    private val keyShowShortsTab = booleanPreferencesKey("show_shorts_tab")
    /** Shorts are hidden everywhere in the app; this only adds an optional "Shorts" tab to channel pages. */
    val showShortsTab: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyShowShortsTab] ?: false }
    suspend fun setShowShortsTab(v: Boolean) { context.userPrefsDataStore.edit { it[keyShowShortsTab] = v } }

    // ---------------- per-video speed, watch-later cleanup, channel groups, app lock ----------------
    private val keyVideoSpeeds = stringPreferencesKey("video_speeds_json")
    private val keyAutoCleanWatchLater = booleanPreferencesKey("auto_clean_watch_later")
    private val keyChannelGroups = stringPreferencesKey("channel_groups_json")
    private val keyAutoDownloadUpdates = booleanPreferencesKey("auto_download_updates")

    /** Video id → speed remembered for that one video (newest 300 are kept). Wins over channel and global speed. */
    val videoSpeeds: Flow<Map<String, Float>> = context.userPrefsDataStore.data.map { decodeSpeeds(it[keyVideoSpeeds]) }
    suspend fun setVideoSpeed(videoId: String, speed: Float?) {
        context.userPrefsDataStore.edit { prefs ->
            val map = LinkedHashMap(decodeSpeeds(prefs[keyVideoSpeeds]))
            map.remove(videoId)                       // re-insert at the end = most recent
            if (speed != null) map[videoId] = speed
            while (map.size > 300) map.remove(map.keys.first())
            prefs[keyVideoSpeeds] = encodeSpeeds(map)
        }
    }

    /** Signed in: a video that is 90 % watched is removed from YouTube's Watch later list. */
    val autoCleanWatchLater: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyAutoCleanWatchLater] ?: false }
    suspend fun setAutoCleanWatchLater(v: Boolean) { context.userPrefsDataStore.edit { it[keyAutoCleanWatchLater] = v } }

    /** Group name → channel URLs (see ChannelGroups). */
    val channelGroups: Flow<Map<String, List<String>>> =
        context.userPrefsDataStore.data.map { com.youtubly.app.domain.library.ChannelGroups.decode(it[keyChannelGroups]) }
    suspend fun setChannelGroups(groups: Map<String, List<String>>) {
        context.userPrefsDataStore.edit { it[keyChannelGroups] = com.youtubly.app.domain.library.ChannelGroups.encode(groups) }
    }

    /** The playlist a video was last saved to (for the one-tap "Save to <playlist>" in the share sheet). 0 = none. */
    val lastPlaylistId: Flow<Long> = context.userPrefsDataStore.data.map { it[longPreferencesKey("last_playlist_id")] ?: 0L }
    suspend fun setLastPlaylistId(v: Long) { context.userPrefsDataStore.edit { it[longPreferencesKey("last_playlist_id")] = v } }

    /** Download a new build in the background on Wi-Fi so installing it is one tap. */
    val autoDownloadUpdates: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyAutoDownloadUpdates] ?: true }
    suspend fun setAutoDownloadUpdates(v: Boolean) { context.userPrefsDataStore.edit { it[keyAutoDownloadUpdates] = v } }

    // ---------------- app updates (GitHub Releases) ----------------
    private val keyAutoUpdateCheck = booleanPreferencesKey("auto_update_check")
    private val keySkippedBuild = intPreferencesKey("update_skipped_build")
    private val keyNotifiedBuild = intPreferencesKey("update_notified_build")
    private val keyLastSeenBuild = intPreferencesKey("update_last_seen_build")
    private val keyLastUpdateCheck = longPreferencesKey("update_last_check")
    private val keyGithubToken = stringPreferencesKey("update_github_token")

    val autoUpdateCheck: Flow<Boolean> = context.userPrefsDataStore.data.map { it[keyAutoUpdateCheck] ?: true }
    suspend fun setAutoUpdateCheck(v: Boolean) { context.userPrefsDataStore.edit { it[keyAutoUpdateCheck] = v } }

    /** A build the user chose to skip: no banner / notification for it (a newer build shows again). */
    val skippedBuild: Flow<Int> = context.userPrefsDataStore.data.map { it[keySkippedBuild] ?: 0 }
    suspend fun setSkippedBuild(v: Int) { context.userPrefsDataStore.edit { it[keySkippedBuild] = v } }

    val notifiedBuild: Flow<Int> = context.userPrefsDataStore.data.map { it[keyNotifiedBuild] ?: 0 }
    suspend fun setNotifiedBuild(v: Int) { context.userPrefsDataStore.edit { it[keyNotifiedBuild] = v } }

    /** Last build number that ran; lets the app say "Youtubly was updated to …" once after an update. */
    val lastSeenBuild: Flow<Int> = context.userPrefsDataStore.data.map { it[keyLastSeenBuild] ?: 0 }
    suspend fun setLastSeenBuild(v: Int) { context.userPrefsDataStore.edit { it[keyLastSeenBuild] = v } }

    val lastUpdateCheck: Flow<Long> = context.userPrefsDataStore.data.map { it[keyLastUpdateCheck] ?: 0L }
    suspend fun setLastUpdateCheck(v: Long) { context.userPrefsDataStore.edit { it[keyLastUpdateCheck] = v } }

    /** Optional fine-grained GitHub token (Contents: read) — only needed if the repository is private. */
    val githubToken: Flow<String> = context.userPrefsDataStore.data.map { it[keyGithubToken] ?: "" }
    suspend fun setGithubToken(v: String) { context.userPrefsDataStore.edit { it[keyGithubToken] = v.trim() } }
}

/**
 * Small description of a hidden video or blocked channel, so the Hidden manager can show a
 * title and thumbnail instead of a bare id. [keys] holds every filter key the item added
 * (for channels: URL and "name:<n>"), so restoring removes all of them.
 */
data class HiddenRecord(
    val title: String,
    val subtitle: String? = null,
    val thumbnailUrl: String? = null,
    val url: String? = null,
    val keys: List<String> = emptyList(),
    val hiddenAt: Long = 0
) {
    companion object {
        fun decodeMap(json: String?): Map<String, HiddenRecord> {
            if (json.isNullOrBlank()) return emptyMap()
            return runCatching {
                val root = org.json.JSONObject(json)
                root.keys().asSequence().associateWith { k ->
                    val o = root.getJSONObject(k)
                    val keysArr = o.optJSONArray("keys")
                    HiddenRecord(
                        title = o.optString("title"),
                        subtitle = o.optString("subtitle").ifEmpty { null },
                        thumbnailUrl = o.optString("thumb").ifEmpty { null },
                        url = o.optString("url").ifEmpty { null },
                        keys = if (keysArr == null) emptyList() else (0 until keysArr.length()).map { keysArr.getString(it) },
                        hiddenAt = o.optLong("at")
                    )
                }
            }.getOrDefault(emptyMap())
        }

        fun encodeMap(map: Map<String, HiddenRecord>): String {
            val root = org.json.JSONObject()
            map.forEach { (k, r) ->
                root.put(
                    k,
                    org.json.JSONObject()
                        .put("title", r.title)
                        .put("subtitle", r.subtitle ?: "")
                        .put("thumb", r.thumbnailUrl ?: "")
                        .put("url", r.url ?: "")
                        .put("keys", org.json.JSONArray(r.keys))
                        .put("at", r.hiddenAt)
                )
            }
            return root.toString()
        }
    }
}

/** SponsorBlock category ids (API names) with labels, in display order. */
object SponsorCategories {
    val ALL: List<Pair<String, String>> = listOf(
        "sponsor" to "Sponsor",
        "selfpromo" to "Self-promotion",
        "interaction" to "Like / subscribe reminder",
        "intro" to "Intro animation",
        "outro" to "Outro / end cards",
        "preview" to "Preview / recap",
        "music_offtopic" to "Non-music section (music videos)",
        "filler" to "Filler / tangent",
    )
    /** Same as before this setting existed, so nothing changes until the user edits it. */
    val DEFAULT_SKIP: Set<String> = setOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "music_offtopic")
}
