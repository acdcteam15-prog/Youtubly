package com.youtubly.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.youtubly.app.core.Constants
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class YoutublyApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject @Named("images") lateinit var imageOkHttpClient: OkHttpClient
    @Inject lateinit var editRepository: com.youtubly.app.data.repository.EditRepository
    @Inject lateinit var playlistAutoFinisher: com.youtubly.app.data.repository.PlaylistAutoFinisher
    @Inject lateinit var blurStore: com.youtubly.app.blur.BlurPreferencesStore
    @Inject lateinit var musicStore: com.youtubly.app.music.MusicPreferencesStore

    override fun onCreate() {
        super.onCreate()
        com.youtubly.app.core.ErrorLog.init(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(Constants.DOWNLOAD_NOTIFICATION_CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(Constants.PLAYBACK_NOTIFICATION_CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(Constants.EDITOR_NOTIFICATION_CHANNEL_ID, "Video editor", NotificationManager.IMPORTANCE_LOW)
        )
        // Videos watched in auto-remove playlists leave them (and finished playlists move to the shelf).
        playlistAutoFinisher.start()
        com.youtubly.app.security.AppLockState.init(this)
        // Crash loop (two crashes soon after starting): switch the risky features off so the app opens again.
        if (com.youtubly.app.security.CrashGuard.install(this)) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                val blurOn = runCatching { blurStore.settings.first().enabled }.getOrDefault(false)
                val musicOn = runCatching { musicStore.settings.first().enabled }.getOrDefault(false)
                com.youtubly.app.security.CrashGuard.remember(this@YoutublyApplication, blurOn, musicOn)
                runCatching { blurStore.setEnabled(false); musicStore.setEnabled(false) }
                com.youtubly.app.playback.GpuEffects.suspendFor(this@YoutublyApplication, 24 * 3600_000L)
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { com.youtubly.app.security.CrashGuard.markStable(this) }, com.youtubly.app.security.CrashGuard.stableAfterMs()
        )
        // Twice a day: look for a new build on GitHub and notify once per build.
        com.youtubly.app.update.UpdateCheckWorker.schedule(this)
        // Exports queued before a force-stop / reboot continue on the next launch.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { editRepository.resumePendingIfAny() }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Coil calls this once, lazily, the first time an AsyncImage needs to load — so every
     *  thumbnail across Home/Search/Channel/Library/Downloads shares one properly-sized cache
     *  instead of each screen implicitly building its own default (25% of available memory,
     *  2% of disk — fine defaults for a single image-heavy screen, wasteful duplicated app-wide,
     *  and not persistent-feeling enough for a feed the user scrolls back through constantly). */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        // Network access (Coil 3 ships it as a separate module): reuse the app's image-tuned OkHttp client.
        .components { add(OkHttpNetworkFetcherFactory(callFactory = imageOkHttpClient)) }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("thumbnail_cache").toOkioPath())
                .maxSizeBytes(200L * 1024 * 1024) // 200MB — generous for a thumbnail-only cache, still a small slice of typical device storage
                .build()
        }
        .build()
}
