package com.youtubly.app.presentation.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
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
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    AuroraBackground {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                LargeTopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    scrollBehavior = scroll,
                )
            },
        ) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).nestedScroll(scroll.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = 32.dp + com.youtubly.app.presentation.components.miniBarClearance())
            ) {
                item { SectionTitle("Content blur") }
                item {
                    Card(Modifier.staggeredEntrance(0)) {
                        NavRow(Icons.Rounded.FaceRetouchingOff, "Content blur", "Hide faces in thumbnails and videos", { onOpenRoute(Screen.BlurSettings.route) })
                    }
                }

                item { SectionTitle("Music remover") }
                item {
                    Card(Modifier.staggeredEntrance(1)) {
                        NavRow(
                            if (music.enabled) Icons.Rounded.MusicOff else Icons.Rounded.MusicNote, "Music remover",
                            if (music.enabled) "On · keeps voices, removes background music" else "Off · keep only voices in videos",
                            { onOpenRoute(Screen.MusicRemover.route) }
                        )
                    }
                }

                item { SectionTitle("SponsorBlock") }
                item {
                    Card(Modifier.staggeredEntrance(2)) {
                        ToggleRow(Icons.Rounded.Shield, "Auto-skip sponsors", "SponsorBlock", autoSkip, viewModel::setAutoSkip)
                        Spacer(Modifier.height(14.dp))
                        NavRow(Icons.Rounded.Tune, "SponsorBlock categories", "Choose which parts get skipped", { onOpenRoute(Screen.SponsorBlock.route) })
                        Spacer(Modifier.height(14.dp))
                        ToggleRow(Icons.Rounded.AutoAwesome, "DeArrow", "Replace clickbait titles & thumbnails", deArrow, viewModel::setDeArrow)
                    }
                }

                item { SectionTitle("Playback") }
                item {
                    Card(Modifier.staggeredEntrance(3)) {
                        SeekStepRow(seekStep, viewModel::setSeekStep)
                        Spacer(Modifier.height(14.dp))
                        SwipeActionsSettings(swipe, viewModel::setSwipeRight, viewModel::setSwipeLeft, viewModel::setSwipeDistance)
                        Spacer(Modifier.height(14.dp))
                        ToggleRow(
                            Icons.Rounded.Bedtime, "Bedtime mode",
                            "Dark, warm screen and quieter videos from ${hhmm(bedtimeStart)} to ${hhmm(bedtimeEnd)}",
                            bedtimeOn, viewModel::setBedtimeOn
                        )
                        AnimatedVisibility(bedtimeOn) {
                            BedtimeDetails(
                                start = bedtimeStart, end = bedtimeEnd, volume = bedtimeVolume,
                                onStart = viewModel::setBedtimeStart, onEnd = viewModel::setBedtimeEnd, onVolume = viewModel::setBedtimeVolume
                            )
                        }
                        ToggleRow(Icons.Rounded.SkipNext, "Autoplay", "Play the next video in a playlist when one ends", autoplayNext, viewModel::setAutoplayNext)
                        Spacer(Modifier.height(14.dp))
                        ToggleRow(Icons.Rounded.Smartphone, "Shorts tab on channels", "Shorts stay hidden everywhere else; this adds a separate tab on channel pages", showShortsTab, viewModel::setShowShortsTab)
                        Spacer(Modifier.height(14.dp))
                        ToggleRow(Icons.Rounded.PictureInPictureAlt, "Picture-in-picture", "Keep watching in a floating window when you leave", autoPip, viewModel::setAutoPip)
                        Spacer(Modifier.height(14.dp))
                        ToggleRow(Icons.Rounded.Headphones, "Keep audio when screen is off", "Drops the video to save data; pauses if off", backgroundAudio, viewModel::setBackgroundAudio)
                        Spacer(Modifier.height(14.dp))
                        ToggleRow(Icons.Rounded.ScreenRotation, "Rotate to fullscreen", "Phones: turning sideways opens fullscreen, turning back exits", rotateToFullscreen, viewModel::setRotateToFullscreen)
                        Spacer(Modifier.height(14.dp))
                        ToggleRow(
                            Icons.Rounded.VisibilityOff, "Incognito",
                            "Don't save history, resume points or searches. Turns off when the app restarts",
                            incognito, com.youtubly.app.core.Incognito::set
                        )
                    }
                }

                item { SectionTitle("Data & quality") }
                item {
                    Card(Modifier.staggeredEntrance(4)) {
                        QualityRow(Icons.Rounded.Wifi, "Wi-Fi quality", wifiQuality, viewModel::setWifiQuality)
                        Spacer(Modifier.height(14.dp))
                        QualityRow(Icons.Rounded.SignalCellularAlt, "Mobile data quality", mobileQuality, viewModel::setMobileQuality)
                        Spacer(Modifier.height(14.dp))
                        ToggleRow(Icons.Rounded.DataSaverOn, "Data saver", "On mobile data: small thumbnails, 360p max, no DeArrow thumbnails", dataSaver, viewModel::setDataSaver)
                        if (dataSaver) {
                            Spacer(Modifier.height(14.dp))
                            ToggleRow(Icons.Rounded.MusicNote, "Start in audio-only", "With data saver on, videos open as audio", dataSaverAudioOnly, viewModel::setDataSaverAudioOnly)
                        }
                    }
                }

                item { SectionTitle("Downloads") }
                item {
                    Card(Modifier.staggeredEntrance(4)) {
                        DownloadRulesSettings(downloadRules, viewModel::setDownloadRules)
                    }
                }

                item { SectionTitle("Appearance") }
                item {
                    Card(Modifier.staggeredEntrance(5)) {
                        AppearanceSettings(themeMode, accent, dynamicColor, tintedSurfaces, viewModel::setThemeMode, viewModel::setAccent, viewModel::setDynamicColor, viewModel::setTintedSurfaces)
                    }
                }

                item { SectionTitle("System") }
                item {
                    Card(Modifier.staggeredEntrance(6)) {
                        NavRow(Icons.Rounded.SystemUpdate, "App updates", "Version ${com.youtubly.app.BuildConfig.VERSION_NAME} · check GitHub for new builds", { onOpenRoute(Screen.Updates.route) })
                        Spacer(Modifier.height(14.dp))
                        NavRow(Icons.Rounded.Storage, "Storage", "See and clear what the app keeps", { onOpenRoute(Screen.Storage.route) })
                        Spacer(Modifier.height(14.dp))
                        NavRow(Icons.Rounded.SettingsBackupRestore, "Backup & restore", "Save your settings to a file (includes blur and music remover)", { onOpenRoute(Screen.Backup.route) })
                        Spacer(Modifier.height(14.dp))
                        NavRow(Icons.Rounded.BugReport, "Diagnostics", "Recent errors, copyable for troubleshooting", { onOpenRoute(Screen.Diagnostics.route) })
                    }
                }
            }
        }
    }
}
