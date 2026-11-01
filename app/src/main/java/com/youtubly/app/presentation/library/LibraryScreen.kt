package com.youtubly.app.presentation.library

import com.youtubly.app.presentation.blur.BlurredAsyncImage
import com.youtubly.app.presentation.navigation.Screen

import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.FaceRetouchingOff
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.youtubly.app.domain.model.AuthState
import com.youtubly.app.domain.model.Video
import com.youtubly.app.presentation.components.AuroraBackground
import com.youtubly.app.presentation.components.GradientTitle
import com.youtubly.app.presentation.components.LocalWatchProgress
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.components.formatCount
import com.youtubly.app.presentation.components.rememberBranding
import com.youtubly.app.presentation.components.shimmer
import com.youtubly.app.presentation.components.staggeredEntrance
import com.youtubly.app.presentation.theme.BrandGradient

@Composable
fun LibraryScreen(
    onVideoClick: (Video) -> Unit,
    onChannelClick: (String) -> Unit,
    onSignIn: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenHidden: () -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    onOpenSubscriptions: () -> Unit = {},
    onOpenBlur: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** Opens a settings sub-screen by route (Diagnostics, Storage, Backup, SponsorBlock). */
    onOpenRoute: (String) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val auth by viewModel.authState.collectAsStateWithLifecycle()
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
    val history by viewModel.history.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val signedIn = auth is AuthState.SignedIn

    AuroraBackground {
        // Pull to refresh the account data (Watch later, playlists, profile). Only when signed
        // in — local history reads straight from Room and is already live.
        com.youtubly.app.presentation.components.Refreshable(
            refreshing = signedIn && account.loading,
            onRefresh = { if (signedIn) viewModel.loadAccount() },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 120.dp + com.youtubly.app.presentation.components.miniBarClearance())
        ) {
            item { GradientTitle("You", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }

            // ---- Settings (everything lives on its own page now) ----
            item {
                Card(Modifier.staggeredEntrance(0)) {
                    NavRow(Icons.Rounded.Settings, "Settings", "Blur, music remover, SponsorBlock, playback, appearance…", onOpenSettings)
                }
            }

            // ---- Account card ----
            item {
                Card(Modifier.staggeredEntrance(0)) {
                    AnimatedContent(
                        targetState = Triple(signedIn, account.profile, account.loading),
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
                        label = "auth"
                    ) { (isIn, profile, loading) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(BrandGradient)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .then(if (loading) Modifier.shimmer() else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                if (profile?.avatarUrl != null) {
                                    coil3.compose.AsyncImage(profile.avatarUrl, profile.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                } else {
                                    Icon(Icons.Rounded.AccountCircle, null, tint = Color.White, modifier = Modifier.size(34.dp))
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        !isIn -> "Guest mode"
                                        profile != null -> profile.name
                                        loading -> "Loading account…"
                                        else -> "Signed in"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    when {
                                        !isIn -> "Sign in to get your own YouTube Home"
                                        profile != null -> listOfNotNull(
                                            profile.handle,
                                            profile.subscriberCount?.let { "${formatCount(it)} subscribers" }
                                        ).joinToString("  ·  ").ifBlank { "YouTube account" }
                                        else -> "Your Home feed is personalised"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isIn) {
                                IconButton(onClick = viewModel::loadAccount) { Icon(Icons.Rounded.Refresh, "Refresh") }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (signedIn) {
                        OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Logout, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Sign out")
                        }
                    } else {
                        Button(
                            onClick = onSignIn,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("Sign in to YouTube") }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        account.error != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Text(
                            account.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }

            item {
                Card(Modifier.staggeredEntrance(1)) {
                    NavRow(Icons.Rounded.PlaylistPlay, "Playlists", "Your local playlists — play, shuffle, reorder", onOpenPlaylists)
                    Spacer(Modifier.height(10.dp))
                    NavRow(Icons.Rounded.History, "History", "Search, remove or pause your watch history", onOpenHistory)
                    Spacer(Modifier.height(10.dp))
                    NavRow(Icons.Rounded.VisibilityOff, "Hidden videos & channels", "Undo \"Not interested\" and \"Don't recommend\"", onOpenHidden)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        Spacer(Modifier.height(10.dp))
                        NavRow(Icons.Rounded.Link, "Open YouTube links in Youtubly", "Tap → \"Add link\" and tick youtube.com / youtu.be") {
                            // Android 12+ only opens unverified web links in an app once the user allows them here.
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                                        android.net.Uri.parse("package:${ctx.packageName}")
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (signedIn && account.watchLater.isNotEmpty()) {
                item { SectionTitle("Watch later · ${account.watchLater.size}") }
                item { VideoRow(account.watchLater, onVideoClick) }
            }
            if (signedIn && account.liked.isNotEmpty()) {
                item { SectionTitle("Liked videos") }
                item { VideoRow(account.liked, onVideoClick) }
            }
            if (history.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionTitle("Recently watched") }
                        androidx.compose.material3.TextButton(onClick = onOpenHistory, modifier = Modifier.padding(end = 8.dp, top = 8.dp)) { Text("See all") }
                    }
                }
                item { VideoRow(history, onVideoClick) }
            }

            // ---- Subscribed channels ----
            if (signedIn && account.channels.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionTitle("Subscriptions · ${account.channels.size}") }
                        androidx.compose.material3.TextButton(onClick = onOpenSubscriptions, modifier = Modifier.padding(end = 8.dp, top = 8.dp)) { Text("Manage") }
                    }
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        itemsIndexed(account.channels, key = { _, c -> c.channelId }) { i, ch ->
                            Column(
                                Modifier.width(76.dp).staggeredEntrance(i).bounceClick { onChannelClick(ch.url) },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
                                        .padding(3.dp)
                                        .clip(CircleShape)
                                        .shimmer()
                                ) {
                                    BlurredAsyncImage(ch.avatarUrl, ch.name, channel = com.youtubly.app.blur.channelKeyOf(ch.channelId), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    ch.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun VideoRow(videos: List<Video>, onVideoClick: (Video) -> Unit) {
    val progressMap = LocalWatchProgress.current.value
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(videos, key = { i, v -> "${v.id}_$i" }) { i, v ->
            val branding = rememberBranding(v.id)
            val progress = progressMap[v.id] ?: 0f
            Column(Modifier.width(200.dp).staggeredEntrance(i).bounceClick { onVideoClick(v) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).shimmer()) {
                    BlurredAsyncImage(branding?.thumbnailUrl ?: com.youtubly.app.presentation.components.thumbnailFor(v.thumbnailUrl), null, channel = com.youtubly.app.blur.channelKeyOf(v.uploaderUrl), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (progress > 0.01f) {
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    branding?.title ?: v.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(v.uploaderName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSettings(
    mode: String,
    accent: Int,
    dynamicColor: Boolean,
    tinted: Boolean,
    onMode: (String) -> Unit,
    onAccent: (Int) -> Unit,
    onDynamic: (Boolean) -> Unit,
    onTinted: (Boolean) -> Unit
) {
    // Wallpaper colours need Android 12+; on older devices the toggle is hidden entirely.
    val dynamicSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val swatchesEnabled = !(dynamicSupported && dynamicColor)
    Column {
        Text("Theme", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.youtubly.app.presentation.theme.ThemeMode.entries.forEach { m ->
                androidx.compose.material3.FilterChip(
                    selected = mode == m.name,
                    onClick = { onMode(m.name) },
                    label = { Text(m.label) },
                    shape = MaterialTheme.shapes.extraLarge
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (dynamicSupported) {
            ToggleRow(Icons.Rounded.Wallpaper, "Use wallpaper colours", "Material You — overrides the accent below", dynamicColor, onDynamic)
            Spacer(Modifier.height(14.dp))
        }
        val accents = com.youtubly.app.presentation.theme.Accents
        val singles = accents.withIndex().filter { it.value.secondary == null }
        val palettes = accents.withIndex().filter { it.value.secondary != null }
        val dim = if (swatchesEnabled) 1f else 0.38f
        Column(
            Modifier.graphicsLayer { alpha = dim }
                .then(if (swatchesEnabled) Modifier else Modifier.semantics { disabled() })
        ) {
            Text("Accent colour", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                singles.forEach { (i, a) -> AccentSwatch(a, selected = swatchesEnabled && i == accent, enabled = swatchesEnabled) { onAccent(i) } }
            }
            Spacer(Modifier.height(16.dp))
            Text("Palettes", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Set the accent plus matching secondary colours",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                palettes.forEach { (i, a) -> AccentSwatch(a, selected = swatchesEnabled && i == accent, enabled = swatchesEnabled, showName = true) { onAccent(i) } }
            }
        }
        Spacer(Modifier.height(14.dp))
        ToggleRow(
            Icons.Rounded.Palette,
            "Tinted surfaces",
            if (mode == "BLACK") "Colours cards and sheets; the background stays pure black" else "Colours the background and cards with your accent",
            tinted,
            onTinted
        )
    }
}

/** One colour choice: a circle (split diagonally for palettes) with an optional caption. */
@Composable
private fun AccentSwatch(
    a: com.youtubly.app.presentation.theme.Accent,
    selected: Boolean,
    enabled: Boolean,
    showName: Boolean = false,
    onClick: () -> Unit
) {
    val fill = if (a.secondary != null) {
        androidx.compose.ui.graphics.Brush.linearGradient(
            0.5f to a.color, 0.5f to a.secondary,
            start = androidx.compose.ui.geometry.Offset.Zero,
            end = androidx.compose.ui.geometry.Offset.Infinite
        )
    } else androidx.compose.ui.graphics.SolidColor(a.color)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(fill)
                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                .then(if (enabled) Modifier.bounceClick(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Rounded.Check, a.name, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        if (showName) {
            Spacer(Modifier.height(4.dp))
            Text(a.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

internal fun hhmm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

/** Start / end times (system time picker) and the volume cap, under the Bedtime toggle. */
@Composable
internal fun BedtimeDetails(start: Int, end: Int, volume: Int, onStart: (Int) -> Unit, onEnd: (Int) -> Unit, onVolume: (Int) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun pick(initial: Int, onPick: (Int) -> Unit) {
        android.app.TimePickerDialog(
            ctx, { _, h, m -> onPick(h * 60 + m) }, initial / 60, initial % 60,
            android.text.format.DateFormat.is24HourFormat(ctx)
        ).show()
    }
    Column(Modifier.padding(start = 38.dp, top = 4.dp, bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.AssistChip(onClick = { pick(start, onStart) }, label = { Text("From ${hhmm(start)}") })
            androidx.compose.material3.AssistChip(onClick = { pick(end, onEnd) }, label = { Text("Until ${hhmm(end)}") })
        }
        Spacer(Modifier.height(6.dp))
        Text("Video volume during bedtime", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(40, 60, 80, 100).forEach { pct ->
                androidx.compose.material3.FilterChip(
                    selected = volume == pct, onClick = { onVolume(pct) },
                    label = { Text(if (pct == 100) "No cap" else "$pct%") }
                )
            }
        }
    }
}

/** Download rules: Wi-Fi only, minimum battery, and an overnight window for new downloads. */
@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadRulesSettings(rules: com.youtubly.app.domain.download.DownloadRules, onChange: (com.youtubly.app.domain.download.DownloadRules) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun pickHour(initial: Int, onPick: (Int) -> Unit) {
        android.app.TimePickerDialog(ctx, { _, h, _ -> onPick(h) }, initial, 0, android.text.format.DateFormat.is24HourFormat(ctx)).show()
    }
    Column {
        ToggleRow(Icons.Rounded.Wifi, "Wi-Fi only", "Downloads wait until you're on Wi-Fi (not mobile data)", rules.wifiOnly) { onChange(rules.copy(wifiOnly = it)) }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BatteryAlert, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Pause on low battery", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("Downloads wait below this level unless charging", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 38.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.youtubly.app.domain.download.DownloadRules.BATTERY_STEPS.forEach { pct ->
                androidx.compose.material3.FilterChip(
                    selected = rules.minBatteryPercent == pct, onClick = { onChange(rules.copy(minBatteryPercent = pct)) },
                    label = { Text(if (pct == 0) "Off" else "$pct%") }, shape = MaterialTheme.shapes.extraLarge
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        ToggleRow(Icons.Rounded.Bedtime, "Overnight downloads", "New downloads start only inside the time window below", rules.scheduleEnabled) { onChange(rules.copy(scheduleEnabled = it)) }
        androidx.compose.animation.AnimatedVisibility(rules.scheduleEnabled) {
            Column(Modifier.padding(start = 38.dp, top = 4.dp, bottom = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.AssistChip(onClick = { pickHour(rules.startHour) { onChange(rules.copy(startHour = it)) } }, label = { Text("From %02d:00".format(rules.startHour)) })
                    androidx.compose.material3.AssistChip(onClick = { pickHour(rules.endHour) { onChange(rules.copy(endHour = it)) } }, label = { Text("Until %02d:00".format(rules.endHour)) })
                }
                Text(
                    "Pressing resume on a download always starts it right away.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private val SEEK_STEPS = listOf(5, 10, 15, 30)

/** Swipe distance presets: how far a card must be dragged before the action fires. */
private val SWIPE_DISTANCES = listOf(40 to "Light", 55 to "Normal", 70 to "Deliberate", 85 to "Very firm")

@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeActionsSettings(
    s: com.youtubly.app.presentation.components.SwipeSettings,
    onRight: (Boolean) -> Unit,
    onLeft: (Boolean) -> Unit,
    onDistance: (Int) -> Unit,
) {
    Column {
        ToggleRow(Icons.Rounded.SwipeRight, "Swipe right: Not interested", "Hides that video", s.right, onRight)
        Spacer(Modifier.height(14.dp))
        ToggleRow(Icons.Rounded.SwipeLeft, "Swipe left: Don't recommend channel", "Hides every video from that channel", s.left, onLeft)
        androidx.compose.animation.AnimatedVisibility(s.right || s.left) {
            Column(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Straighten, null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Swipe distance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text("How far to drag a card (${s.distancePct}% of its width). Quick flicks never count",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.padding(start = 38.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SWIPE_DISTANCES.forEach { (pct, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = s.distancePct == pct,
                            onClick = { onDistance(pct) },
                            label = { Text(label) },
                            shape = MaterialTheme.shapes.extraLarge
                        )
                    }
                }
            }
        }
    }
}

@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SeekStepRow(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FastForward, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Seek step", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("Double-tap the sides of the video; each extra tap adds a step", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(start = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SEEK_STEPS.forEach { sec ->
                androidx.compose.material3.FilterChip(
                    selected = selected == sec,
                    onClick = { onSelect(sec) },
                    label = { Text("$sec s") },
                    shape = MaterialTheme.shapes.extraLarge
                )
            }
        }
    }
}

private val QUALITY_OPTIONS = listOf(0, 1080, 720, 480, 360)

@kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun QualityRow(icon: ImageVector, title: String, selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("Applies to the next video you open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.padding(start = 38.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QUALITY_OPTIONS.forEach { q ->
                androidx.compose.material3.FilterChip(
                    selected = selected == q,
                    onClick = { onSelect(q) },
                    label = { Text(if (q == 0) "Auto" else "${q}p") },
                    shape = MaterialTheme.shapes.extraLarge
                )
            }
        }
    }
}

@Composable
internal fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).bounceClick(scaleDown = 0.98f, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 10.dp)
    )
}

@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f))
            .padding(16.dp)
    ) { content() }
}
