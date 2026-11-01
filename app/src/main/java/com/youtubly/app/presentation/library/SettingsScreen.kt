package com.youtubly.app.presentation.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import com.youtubly.app.presentation.components.SimpleTopBar
import com.youtubly.app.presentation.components.PlainBackground
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.FaceRetouchingOff
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.presentation.components.AuroraBackground
import com.youtubly.app.presentation.components.staggeredEntrance
import com.youtubly.app.presentation.navigation.Screen

/**
 * All app settings in one page (moved off the You tab). Sections: Content blur, Music remover,
 * SponsorBlock, Playback, Data & quality, Appearance, System. Rows are the same composables the
 * You tab used (LibraryScreen.kt), so behaviour is unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
    musicVm: com.youtubly.app.presentation.music.MusicRemoverViewModel = hiltViewModel(),
) {
    val autoSkip by viewModel.autoSkipSponsors.collectAsStateWithLifecycle()
    val deArrow by viewModel.deArrow.collectAsStateWithLifecycle()
    val autoPip by viewModel.autoPip.collectAsStateWithLifecycle()
    val autoplayNext by viewModel.autoplayNext.collectAsStateWithLifecycle()
    val incognito by com.youtubly.app.core.Incognito.on.collectAsStateWithLifecycle()
    val bedtimeOn by viewModel.bedtimeOn.collectAsStateWithLifecycle()
    val bedtimeStart by viewModel.bedtimeStart.collectAsStateWithLifecycle()
    val bedtimeEnd by viewModel.bedtimeEnd.collectAsStateWithLifecycle()
    val bedtimeVolume by viewModel.bedtimeVolume.collectAsStateWithLifecycle()
    val backgroundAudio by viewModel.backgroundAudio.collectAsStateWithLifecycle()
    val showShortsTab by viewModel.showShortsTab.collectAsStateWithLifecycle()
    val downloadRules by viewModel.downloadRules.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val accent by viewModel.accent.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val tintedSurfaces by viewModel.tintedSurfaces.collectAsStateWithLifecycle()
    val seekStep by viewModel.seekStep.collectAsStateWithLifecycle()
    val swipe by viewModel.swipe.collectAsStateWithLifecycle()
    val rotateToFullscreen by viewModel.rotateToFullscreen.collectAsStateWithLifecycle()
    val wifiQuality by viewModel.wifiQuality.collectAsStateWithLifecycle()
    val mobileQuality by viewModel.mobileQuality.collectAsStateWithLifecycle()
    val dataSaver by viewModel.dataSaver.collectAsStateWithLifecycle()
    val dataSaverAudioOnly by viewModel.dataSaverAudioOnly.collectAsStateWithLifecycle()
    val music by musicVm.settings.collectAsStateWithLifecycle()
    val autoCleanWatchLater by viewModel.autoCleanWatchLater.collectAsStateWithLifecycle()
    val appLockOn by com.youtubly.app.security.AppLockState.enabled.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    PlainBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SimpleTopBar("Settings", onBack)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 32.dp + com.youtubly.app.presentation.components.miniBarClearance())
            ) {
                item { SectionTitle("Features") }
                item {
                    Card {
                        NavRow(Icons.Rounded.FaceRetouchingOff, "Content blur", "Hide faces in thumbnails and videos", { onOpenRoute(Screen.BlurSettings.route) })
                        NavRow(
                            if (music.enabled) Icons.Rounded.MusicOff else Icons.Rounded.MusicNote, "Music remover",
                            if (music.enabled) "On - keeps voices, removes music" else "Off",
                            { onOpenRoute(Screen.MusicRemover.route) }
                        )
                        NavRow(Icons.Rounded.Tune, "SponsorBlock", "Choose which parts get skipped", { onOpenRoute(Screen.SponsorBlock.route) })
                        ToggleRow(Icons.Rounded.Shield, "Auto-skip sponsors", "", autoSkip, viewModel::setAutoSkip)
                        ToggleRow(Icons.Rounded.AutoAwesome, "DeArrow", "Replace clickbait titles and thumbnails", deArrow, viewModel::setDeArrow)
                    }
                }

                item { SectionTitle("Playback") }
                item {
                    Card {
                        SeekStepRow(seekStep, viewModel::setSeekStep)
                        SwipeActionsSettings(swipe, viewModel::setSwipeRight, viewModel::setSwipeLeft, viewModel::setSwipeDistance)
                        ToggleRow(Icons.Rounded.SkipNext, "Autoplay", "Play the next video when one ends", autoplayNext, viewModel::setAutoplayNext)
                        ToggleRow(Icons.Rounded.PictureInPictureAlt, "Picture-in-picture", "Keep watching in a small window when you leave", autoPip, viewModel::setAutoPip)
                        ToggleRow(Icons.Rounded.Headphones, "Keep audio with screen off", "Saves data; pauses if turned off", backgroundAudio, viewModel::setBackgroundAudio)
                        ToggleRow(Icons.Rounded.ScreenRotation, "Rotate to fullscreen", "Turn the phone sideways to go fullscreen", rotateToFullscreen, viewModel::setRotateToFullscreen)
                    }
                }

                item { SectionTitle("Data and quality") }
                item {
                    Card {
                        QualityRow(Icons.Rounded.Wifi, "Wi-Fi quality", wifiQuality, viewModel::setWifiQuality)
                        QualityRow(Icons.Rounded.SignalCellularAlt, "Mobile data quality", mobileQuality, viewModel::setMobileQuality)
                        ToggleRow(Icons.Rounded.DataSaverOn, "Data saver", "On mobile data: small thumbnails, 360p at most", dataSaver, viewModel::setDataSaver)
                        if (dataSaver) {
                            ToggleRow(Icons.Rounded.MusicNote, "Start in audio-only", "With data saver on, videos open as audio", dataSaverAudioOnly, viewModel::setDataSaverAudioOnly)
                        }
                    }
                }

                item { SectionTitle("Downloads") }
                item { Card { DownloadRulesSettings(downloadRules, viewModel::setDownloadRules) } }

                item { SectionTitle("Appearance") }
                item {
                    Card {
                        AppearanceSettings(themeMode, accent, dynamicColor, tintedSurfaces, viewModel::setThemeMode, viewModel::setAccent, viewModel::setDynamicColor, viewModel::setTintedSurfaces)
                    }
                }

                item { SectionTitle("More") }
                item {
                    Card {
                        ToggleRow(
                            Icons.Rounded.Bedtime, "Bedtime mode",
                            "Warm, dim screen and quieter videos ${hhmm(bedtimeStart)} to ${hhmm(bedtimeEnd)}",
                            bedtimeOn, viewModel::setBedtimeOn
                        )
                        AnimatedVisibility(bedtimeOn) {
                            BedtimeDetails(
                                start = bedtimeStart, end = bedtimeEnd, volume = bedtimeVolume,
                                onStart = viewModel::setBedtimeStart, onEnd = viewModel::setBedtimeEnd, onVolume = viewModel::setBedtimeVolume
                            )
                        }
                        ToggleRow(Icons.Rounded.Smartphone, "Shorts tab on channels", "A separate tab on channel pages", showShortsTab, viewModel::setShowShortsTab)
                        ToggleRow(
                            Icons.Rounded.WatchLater, "Clean up Watch later",
                            "Signed in: remove a video from YouTube's Watch later once you've watched it",
                            autoCleanWatchLater, viewModel::setAutoCleanWatchLater
                        )
                        ToggleRow(
                            Icons.Rounded.Lock, "App lock",
                            "Ask for your fingerprint or screen lock to open Youtubly (also hides it in recent apps)",
                            appLockOn
                        ) { on ->
                            if (on && !com.youtubly.app.security.AppLock.isAvailable(ctx)) {
                                android.widget.Toast.makeText(ctx, "Set a screen lock or fingerprint on your phone first", android.widget.Toast.LENGTH_LONG).show()
                            } else com.youtubly.app.security.AppLockState.setEnabled(ctx, on)
                        }
                        ToggleRow(
                            Icons.Rounded.VisibilityOff, "Incognito",
                            "Don't save history or searches until the app restarts",
                            incognito, com.youtubly.app.core.Incognito::set
                        )
                    }
                }

                item { SectionTitle("About") }
                item {
                    Card {
                        NavRow(Icons.Rounded.SystemUpdate, "App updates", "Version ${com.youtubly.app.BuildConfig.VERSION_NAME}", { onOpenRoute(Screen.Updates.route) })
                        NavRow(Icons.Rounded.Storage, "Storage", "See and clear what the app keeps", { onOpenRoute(Screen.Storage.route) })
                        NavRow(Icons.Rounded.SettingsBackupRestore, "Backup and restore", "Save your settings to a file", { onOpenRoute(Screen.Backup.route) })
                        NavRow(Icons.Rounded.BugReport, "Diagnostics", "Recent errors, copyable for troubleshooting", { onOpenRoute(Screen.Diagnostics.route) })
                    }
                }
            }
        }
    }
}
