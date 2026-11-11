package com.youtubly.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.youtubly.app.core.ConnectivityObserver
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.data.repository.DeArrowRepository
import com.youtubly.app.domain.repository.VideoRepository
import com.youtubly.app.playback.PipState
import com.youtubly.app.playback.PlayerController
import com.youtubly.app.presentation.components.LocalDeArrow
import com.youtubly.app.presentation.components.LocalIsOnline
import com.youtubly.app.presentation.components.LocalWatchProgress
import com.youtubly.app.presentation.navigation.YoutublyNavHost
import com.youtubly.app.presentation.theme.YoutublyTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var deArrow: DeArrowRepository
    @Inject lateinit var videoRepository: VideoRepository
    /** Injecting it here also starts it (it's a singleton that watches the clock). */
    @Inject lateinit var bedtime: com.youtubly.app.playback.Bedtime
    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var connectivityObserver: ConnectivityObserver
    @Inject lateinit var videoActions: com.youtubly.app.playback.VideoActions

    @Inject lateinit var networkPolicy: com.youtubly.app.core.NetworkPolicy
    @Inject lateinit var playlistRepository: com.youtubly.app.data.repository.PlaylistRepository
    // Injected so the queue keeps advancing even when no player screen has been opened yet
    // this process (e.g. the floating mini player after a restore).
    @Inject lateinit var queuePlayer: com.youtubly.app.playback.QueuePlayer
    @Inject lateinit var editorInbox: com.youtubly.app.editor.EditorInbox
    @Inject lateinit var sharedVideoImporter: com.youtubly.app.editor.SharedVideoImporter
    @Inject lateinit var blurEngine: com.youtubly.app.blur.BlurEngine
    @Inject lateinit var musicRemover: com.youtubly.app.music.MusicRemover
    @Inject lateinit var queueResume: com.youtubly.app.playback.QueueResume

    // Read once before the first frame so the app doesn't flash the default theme at launch.
    private val initialThemeMode: String by lazy { kotlinx.coroutines.runBlocking { userPreferences.themeMode.first() } }
    private val initialAccent: Int by lazy { kotlinx.coroutines.runBlocking { userPreferences.accent.first() } }
    private val initialDynamic: Boolean by lazy { kotlinx.coroutines.runBlocking { userPreferences.dynamicColor.first() } }
    private val initialTinted: Boolean by lazy { kotlinx.coroutines.runBlocking { userPreferences.tintedSurfaces.first() } }

    /** A YouTube link from a VIEW/SEND intent, waiting for the NavHost to open it. */
    private val pendingLink = kotlinx.coroutines.flow.MutableStateFlow<com.youtubly.app.core.DeepLink?>(null)

    @Volatile private var autoPip = true
    @Volatile private var backgroundAudio = true
    private var lastRequestPip = 0

    // Declaring POST_NOTIFICATIONS in the manifest isn't enough on API 33+ — without an
    // explicit runtime grant, the media-session's background-playback notification (and
    // with it, lock-screen controls) is silently suppressed even though playback continues.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    @OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        com.youtubly.app.presentation.components.OrientationState.init(this)      // phones rest in portrait, whatever was saved
        queueResume.start()
        // Hold the splash until we know whether the first-launch model setup must show (no empty frame).
        splash.setKeepOnScreenCondition { blurEngine.setupNeeded.value == null }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Only on a fresh start: after a recreation the NavHost has already restored the screen.
        if (savedInstanceState == null) handleIntent(intent)

        lifecycleScope.launch { userPreferences.autoPip.collect { autoPip = it } }
        lifecycleScope.launch { userPreferences.backgroundAudio.collect { backgroundAudio = it } }

        setContent {
            val progressFlow = remember { videoRepository.observeProgress() }
            val progress = progressFlow.collectAsState(initial = emptyMap())
            val isOnline = connectivityObserver.isOnline.collectAsState()
            val dataSaver = networkPolicy.dataSaverActive.collectAsState()
            val seekStep = userPreferences.seekStepSeconds.collectAsState(initial = 10)
            val swipeSettings = userPreferences.swipeActions.collectAsState(initial = com.youtubly.app.presentation.components.SwipeSettings())
            val rotateToFullscreen = userPreferences.rotateToFullscreen.collectAsState(initial = false)
            // Recomputed on every configuration change (rotation, fold/unfold, window resize).
            val sizeClass = androidx.compose.material3.windowsizeclass.calculateWindowSizeClass(this)
            val wide = sizeClass.widthSizeClass != androidx.compose.material3.windowsizeclass.WindowWidthSizeClass.Compact &&
                sizeClass.heightSizeClass != androidx.compose.material3.windowsizeclass.WindowHeightSizeClass.Compact
            val pipRequest by PipState.requestPip
            // A manual "enter PiP now" tap from the player screen (guaranteed path —
            // onUserLeaveHint doesn't fire reliably on every OEM gesture-nav setup).
            if (pipRequest != lastRequestPip) {
                lastRequestPip = pipRequest
                tryEnterPip(force = true)
            }
            CompositionLocalProvider(
                LocalDeArrow provides deArrow,
                LocalWatchProgress provides progress,
                LocalIsOnline provides isOnline,
                com.youtubly.app.presentation.components.LocalDataSaver provides dataSaver,
                com.youtubly.app.presentation.components.LocalSeekStepSeconds provides seekStep,
                com.youtubly.app.presentation.components.LocalSwipeSettings provides swipeSettings,
                com.youtubly.app.presentation.components.LocalWideLayout provides wide,
                com.youtubly.app.presentation.components.LocalRotateToFullscreen provides rotateToFullscreen,
                com.youtubly.app.presentation.components.LocalPlaylists provides playlistRepository,
                com.youtubly.app.presentation.components.LocalVideoActions provides videoActions,
                com.youtubly.app.blur.LocalBlurEngine provides blurEngine,
                com.youtubly.app.music.LocalMusicRemover provides musicRemover
            ) {
                val themeMode by userPreferences.themeMode.collectAsState(initial = initialThemeMode)
                val accent by userPreferences.accent.collectAsState(initial = initialAccent)
                val useWallpaper by userPreferences.dynamicColor.collectAsState(initial = initialDynamic)
                val tinted by userPreferences.tintedSurfaces.collectAsState(initial = initialTinted)
                // Bedtime: force a dark theme (Black stays Black) and lay a warm tint over the app.
                val bedtimeActive by bedtime.active.collectAsState()
                val chosenMode = com.youtubly.app.presentation.theme.ThemeMode.from(themeMode)
                val effectiveMode = if (bedtimeActive && chosenMode != com.youtubly.app.presentation.theme.ThemeMode.BLACK)
                    com.youtubly.app.presentation.theme.ThemeMode.DARK else chosenMode
                val warmth by androidx.compose.animation.core.animateFloatAsState(
                    if (bedtimeActive) 1f else 0f, androidx.compose.animation.core.tween(1200), label = "bedtimeWarmth"
                )
                YoutublyTheme(
                    mode = effectiveMode,
                    accent = accent,
                    dynamicColor = useWallpaper,
                    tintedSurfaces = tinted
                ) {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                drawContent()
                                if (warmth > 0f) {
                                    // Night-light tint = a plain translucent warm overlay. Do NOT use an offscreen
                                    // layer + BlendMode.Multiply here: the full-screen player's SurfaceView is a
                                    // transparent hole in the app layer, and multiplying a colour onto a transparent
                                    // pixel just paints that colour, which covered the video with pale peach (looked
                                    // white) for as long as Bedtime was on. A normal-blend overlay composites over
                                    // whatever is behind the hole (the video) instead, so the picture stays visible.
                                    drawRect(androidx.compose.ui.graphics.Color(0xFFFF9A3D).copy(alpha = 0.20f * warmth))
                                }
                            }
                    ) {
                        com.youtubly.app.presentation.blur.ModelSetupGate {
                            com.youtubly.app.security.AppLockGate {
                                YoutublyNavHost(playerController, pendingLink, onLinkHandled = { pendingLink.value = null })
                            }
                        }
                        com.youtubly.app.security.SafeModeDialog()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply the app's orientation (portrait unless a player screen asked otherwise), so a stale landscape request can't linger.
        requestedOrientation = com.youtubly.app.presentation.components.OrientationState.desired
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Browser taps (ACTION_VIEW on youtube.com / youtu.be) and "Share → Youtubly"
     * (ACTION_SEND text) both end up here; the widget uses the same VIEW path.
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // A video file shared from another app (gallery, Files…) → open it in the Edit tab.
        if (intent.action == Intent.ACTION_SEND && (intent.type?.startsWith("video/") == true || intent.type == "application/octet-stream")) {
            // Some apps put the video in EXTRA_STREAM, others only in ClipData.
            val stream: android.net.Uri? = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            }) ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (stream == null) {
                android.widget.Toast.makeText(this, "No video was shared", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            // Copy the video into app storage right here: the read permission that came with the share
            // is guaranteed valid in this activity, whereas a ViewModel picking it up later could lose it,
            // which left the Edit tab open with nothing loaded. The editor then gets a finished local file.
            android.widget.Toast.makeText(this, "Importing video…", android.widget.Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                try {
                    val imported = sharedVideoImporter.import(stream.toString())
                    editorInbox.send(com.youtubly.app.domain.editor.EditorRequest.OpenImported(imported.path, imported.title))
                    pendingLink.value = com.youtubly.app.core.DeepLink.OpenEditor
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    android.util.Log.e("Youtubly", "Shared video import failed", t)
                    com.youtubly.app.core.ErrorLog.record("Share to editor", t)
                    val why = (t as? com.youtubly.app.editor.ExportFailed)?.message ?: t.message ?: t.javaClass.simpleName
                    android.widget.Toast.makeText(this@MainActivity, "Couldn't open that video: $why", android.widget.Toast.LENGTH_LONG).show()
                    pendingLink.value = com.youtubly.app.core.DeepLink.OpenEditor
                }
            }
            return
        }
        // Home-screen shortcuts (res/xml/shortcuts.xml): youtubly://search, downloads, audio, continue.
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "youtubly") {
            when (intent.data?.host) {
                "search" -> pendingLink.value = com.youtubly.app.core.DeepLink.Open(com.youtubly.app.presentation.navigation.Screen.Search.route)
                "playlist" -> intent.data?.lastPathSegment?.toLongOrNull()?.let {
                    pendingLink.value = com.youtubly.app.core.DeepLink.Open(com.youtubly.app.presentation.navigation.Screen.PlaylistDetail.createRoute(it))
                }
                "playlists" -> {
                    com.youtubly.app.presentation.library.LibraryHubState.page = com.youtubly.app.presentation.library.LibraryHubState.PLAYLISTS
                    pendingLink.value = com.youtubly.app.core.DeepLink.Open(com.youtubly.app.presentation.navigation.Screen.Downloads.route)
                }
                "updates" -> pendingLink.value = com.youtubly.app.core.DeepLink.Open(com.youtubly.app.presentation.navigation.Screen.Updates.route)
                "downloads" -> {
                    com.youtubly.app.presentation.library.LibraryHubState.page = com.youtubly.app.presentation.library.LibraryHubState.DOWNLOADS
                    pendingLink.value = com.youtubly.app.core.DeepLink.Open(com.youtubly.app.presentation.navigation.Screen.Downloads.route)
                }
                "audio" -> pendingLink.value = com.youtubly.app.core.DeepLink.Open(com.youtubly.app.presentation.navigation.Screen.AudioLibrary.route)
                "continue" -> lifecycleScope.launch {
                    // The most recent unfinished video in history; otherwise the History screen.
                    val entry = videoRepository.observeHistoryEntries("").first().firstOrNull { it.progress < 0.95f }
                    pendingLink.value = if (entry != null) com.youtubly.app.core.DeepLink.Video(entry.video.url)
                    else com.youtubly.app.core.DeepLink.Open(com.youtubly.app.presentation.navigation.Screen.History.route)
                }
            }
            return
        }
        val link = when (intent.action) {
            Intent.ACTION_VIEW -> com.youtubly.app.core.LinkParser.parse(intent.dataString)
            Intent.ACTION_SEND -> com.youtubly.app.core.LinkParser.parseText(
                intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            )
            else -> return
        }
        if (link == null) {
            android.widget.Toast.makeText(this, "That doesn't look like a YouTube video, playlist or channel link", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // Shared (not tapped) video links ask what to do: play, play next, save or download.
        pendingLink.value = if (intent.action == Intent.ACTION_SEND && link is com.youtubly.app.core.DeepLink.Video) {
            com.youtubly.app.core.DeepLink.Shared(link.url, link.startMs)
        } else link
    }

    private fun pipParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setSeamlessResizeEnabled(true) }
            .build()

    private fun canPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * @param force ignores the auto-PiP setting — used by the manual button, which the user
     * tapped on purpose. The automatic paths below (leaving the app / backgrounding) still
     * respect the "Picture-in-picture" toggle in You → Settings.
     *
     * Eligibility is read straight off PlayerController (a singleton, not Compose-scoped),
     * not PipState.playerOnScreen: that flag is cleared by PlayerScreen's onDispose the
     * instant the composable leaves — e.g. the moment the user taps the Downloads tab — which
     * can beat onStop()/onUserLeaveHint() to the punch and made PiP silently refuse to open
     * whenever backgrounding happened right after switching away from the player screen.
     */
    /** @return true if a PiP window was requested. */
    private fun tryEnterPip(force: Boolean = false): Boolean {
        if (!canPip()) return false
        val eligible = playerController.currentVideoId != null &&
            playerController.player.isPlaying &&
            !playerController.isAudioMode
        if (!eligible) return false
        if (!force && !autoPip) return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setPictureInPictureParams(pipParams())
            }
            enterPictureInPictureMode(pipParams())
        }.getOrDefault(false)
    }

    private fun isScreenOn(): Boolean =
        (getSystemService(POWER_SERVICE) as? android.os.PowerManager)?.isInteractive ?: true

    /**
     * Pending "the PiP window was closed" decision. Several Android builds (Samsung among them)
     * stop the activity for a moment while *expanding* PiP back to full screen, which looked
     * exactly like the user swiping the PiP window away — so playback was torn down and the
     * player reloaded from the network on return (blank details, no settings gear until it
     * finished). We now wait briefly: if the activity starts again, it was an expand.
     */
    // A plain Handler rather than lifecycleScope: that scope dies with the activity, and a
    // real close must still stop playback even if the activity is destroyed meanwhile.
    private val pipHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pipCloseCheck = Runnable {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) closedFromPip()
    }

    private fun scheduleClosedFromPip() {
        pipHandler.removeCallbacks(pipCloseCheck)
        pipHandler.postDelayed(pipCloseCheck, PIP_CLOSE_GRACE_MS)
    }

    override fun onStart() {
        super.onStart()
        // Back on screen within the grace period → the PiP window was expanded, not closed.
        pipHandler.removeCallbacks(pipCloseCheck)
        // Back on screen: bring the video track back if it was dropped for the background.
        playerController.onAppForegrounded()
    }

    /** Home button / recents on most launchers. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        tryEnterPip()
    }

    /**
     * Fallback for launchers/gestures that don't call onUserLeaveHint before the activity
     * pauses (some OEM gesture-nav app-switch animations skip it) — if we're about to stop
     * with a video still on screen and playing, take the PiP window instead of just pausing
     * on the home screen with nothing.
     */
    override fun onStop() {
        val screenOn = isScreenOn()
        when {
            // Locking the phone also stops the activity — including while a PiP window is up.
            // That is not a "close PiP": previously it tore playback down on every screen lock.
            !screenOn -> playerController.onAppBackgrounded(keepAudio = backgroundAudio)
            // Stopping while we're (or just were) a PiP window means the user swiped it away or
            // tapped its ✕ — stop playback like YouTube does.
            isInPictureInPictureMode || PipState.inPip.value -> scheduleClosedFromPip()
            // Plain backgrounding: take a PiP window if allowed, else fall back to audio-only
            // (or pause, per the "Keep audio when screen is off" setting).
            !tryEnterPip() -> playerController.onAppBackgrounded(keepAudio = backgroundAudio)
        }
        super.onStop()
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun closedFromPip() {
        PipState.inPip.value = false
        // Keep "resume where you left off" working for the video we're about to drop.
        val id = playerController.currentVideoId
        val pos = playerController.player.currentPosition
        // GlobalScope on purpose: this can run after the activity (and its lifecycleScope) is gone.
        if (id != null) kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.NonCancellable) { videoRepository.savePosition(id, pos) }
        playerController.stop()
        PipState.lastPlayerRoute.value = null
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode &&
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        ) {
            // Some Android versions deliver this after onStop when the PiP window is closed —
            // and some deliver it the same way while *expanding* it, so decide after a grace period.
            PipState.inPip.value = false
            scheduleClosedFromPip()
            return
        }
        PipState.inPip.value = isInPictureInPictureMode
    }

    private companion object {
        /** Long enough for the expand transition's stop→start; short enough to feel instant on a real close. */
        const val PIP_CLOSE_GRACE_MS = 700L
    }
}
