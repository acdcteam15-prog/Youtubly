package com.youtubly.app.core

import com.youtubly.app.data.local.datastore.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** What a newly loaded video should use on the current network. */
data class NetworkPlaybackPolicy(
    /** Max video height, or null for no cap (Auto). */
    val maxHeight: Int?,
    val metered: Boolean,
    val dataSaver: Boolean,
    val startAudioOnly: Boolean,
    /** The battery is low (below the user's limit, not charging): the video starts as audio only. */
    val lowBattery: Boolean = false
)

/**
 * Combines the network type (Wi-Fi vs mobile data) with the quality / data-saver settings.
 * The policy is read once when a video loads, so a network change mid-video only affects
 * the next video.
 */
@Singleton
class NetworkPolicy @Inject constructor(
    private val connectivity: ConnectivityObserver,
    private val prefs: UserPreferences,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Data saver is on AND we're on a metered network. Drives thumbnails and DeArrow. */
    val dataSaverActive: StateFlow<Boolean> = combine(prefs.dataSaver, connectivity.isMetered) { ds, metered -> ds && metered }
        .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun forNewVideo(): NetworkPlaybackPolicy {
        val metered = connectivity.currentlyMetered()
        val dataSaver = prefs.dataSaver.first() && metered
        val chosen = if (metered) prefs.mobileQuality.first() else prefs.wifiQuality.first()
        val cap = capFor(chosen, dataSaver)
        val lowBattery = LowBattery.listenOnly(batteryPercent(), isCharging(), prefs.lowBatteryAudioPct.first())
        return NetworkPlaybackPolicy(
            maxHeight = cap,
            metered = metered,
            dataSaver = dataSaver,
            startAudioOnly = (dataSaver && prefs.dataSaverAudioOnly.first()) || lowBattery,
            lowBattery = lowBattery
        )
    }

    private fun batteryIntent(): android.content.Intent? =
        runCatching { context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()

    private fun batteryPercent(): Int = batteryIntent()?.let {
        LowBattery.percent(it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1))
    } ?: -1

    private fun isCharging(): Boolean = batteryIntent()?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1).let {
        it == android.os.BatteryManager.BATTERY_STATUS_CHARGING || it == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        const val DATA_SAVER_CAP = 360

        /** [setting] 0 = Auto. With data saver the cap is never above 360p. */
        fun capFor(setting: Int, dataSaver: Boolean): Int? {
            val base = setting.takeIf { it > 0 }
            return if (dataSaver) minOf(base ?: DATA_SAVER_CAP, DATA_SAVER_CAP) else base
        }

        private val THUMB_REGEX = Regex("/(maxresdefault|sddefault|hqdefault|hq720|hq360)(_live)?\\.(jpg|webp)")

        /** Swap a YouTube thumbnail for the small 320×180 "mqdefault" variant. */
        fun smallThumbnail(url: String?): String? {
            if (url == null || !url.contains("ytimg.com")) return url
            val plain = url.substringBefore('?')
            return if (THUMB_REGEX.containsMatchIn(plain)) THUMB_REGEX.replace(plain) { m -> "/mqdefault${m.groupValues[2]}.${m.groupValues[3]}" } else url
        }
    }
}
