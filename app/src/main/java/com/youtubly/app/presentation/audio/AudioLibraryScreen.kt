package com.youtubly.app.presentation.audio

import com.youtubly.app.presentation.blur.BlurredAsyncImage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.youtubly.app.presentation.components.EmptyState
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.formatDuration
import com.youtubly.app.presentation.components.popOnChange
import com.youtubly.app.presentation.components.shimmer
import com.youtubly.app.presentation.components.staggeredEntrance

/**
 * Music-player view of downloads: play all / shuffle, a track list, and a now-playing bar.
 * Playback runs through the app's media session, so the lock screen and notification get
 * play/pause and next/previous too.
 */
@Composable
fun AudioLibraryScreen(onBack: () -> Unit, viewModel: AudioLibraryViewModel = hiltViewModel()) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val now by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val currentId = now?.current?.downloadId
    val eqPreset by viewModel.playerController.eqPreset.collectAsStateWithLifecycle()
    val boostDb by viewModel.playerController.boostDb.collectAsStateWithLifecycle()
    var showSound by remember { mutableStateOf(false) }
    if (showSound) SoundSheet(viewModel.playerController, onDismiss = { showSound = false })

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Audio library", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioSource.entries.forEach { s ->
                    FilterChip(selected = source == s, onClick = { viewModel.setSource(s) }, label = { Text(s.label) }, shape = MaterialTheme.shapes.extraLarge)
                }
            }
            val list = tracks
            when {
                list == null -> SkeletonFeed(count = 3)
                list.isEmpty() -> EmptyState(
                    Icons.Rounded.Headphones,
                    if (source == AudioSource.AUDIO) "No audio downloads yet" else "No downloads yet",
                    if (source == AudioSource.AUDIO) "Download a video as audio from the player, or switch to All downloads." else "Save videos from the player to listen offline."
                )
                else -> {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { viewModel.play() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play all")
                        }
                        FilledTonalButton(onClick = { viewModel.play(shuffle = true) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                        }
                    }
                    Text(
                        "${list.size} track${if (list.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
                    )
                    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 180.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
                        itemsIndexed(list, key = { _, it -> it.id }) { i, t ->
                            val playingThis = t.id == currentId
                            Row(
                                Modifier
                                    .animateItem()
                                    .staggeredEntrance(i)
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.large)
                                    .background(if (playingThis) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { viewModel.play(startId = t.id, shuffle = progress.shuffle) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(52.dp).clip(MaterialTheme.shapes.medium).shimmer(), contentAlignment = Alignment.Center) {
                                    BlurredAsyncImage(t.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    if (playingThis) {
                                        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.GraphicEq, "Now playing", tint = androidx.compose.ui.graphics.Color.White)
                                        }
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        t.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (playingThis) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (playingThis) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    val left = if (t.durationMs > 0 && t.resumeFraction in 0.01f..0.949f) " · ${formatDuration((t.durationMs - t.positionMs) / 1000)} left" else if (t.watchedPercent >= 95) " · Played" else ""
                                    Text(
                                        (if (t.isAudioOnly) "Audio" + (t.qualityLabel?.let { " · $it" } ?: "") else "Video, played as audio") + left,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (t.resumeFraction > 0.01f && t.watchedPercent < 95) {
                                        Spacer(Modifier.height(5.dp))
                                        androidx.compose.material3.LinearProgressIndicator(
                                            progress = { t.resumeFraction },
                                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(androidx.compose.foundation.shape.CircleShape),
                                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Now-playing bar ----
        AnimatedVisibility(
            visible = now != null,
            enter = slideInVertically { it } + fadeIn(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val track = now?.current
            Column(
                Modifier
                    .padding(12.dp)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BlurredAsyncImage(track?.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.medium))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "${(now?.index ?: 0) + 1} of ${now?.tracks?.size ?: 0}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showSound = true }) {
                        Icon(Icons.Rounded.Tune, "Sound: equalizer and volume boost",
                            tint = if (eqPreset != com.youtubly.app.domain.audio.EqPreset.OFF || boostDb > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = viewModel::toggleShuffle) {
                        Icon(Icons.Rounded.Shuffle, if (progress.shuffle) "Shuffle on" else "Shuffle off",
                            tint = if (progress.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                var scrub by remember { mutableStateOf<Float?>(null) }
                val frac = if (progress.durationMs > 0) progress.positionMs.toFloat() / progress.durationMs else 0f
                Slider(
                    value = scrub ?: frac.coerceIn(0f, 1f),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let(viewModel::seekTo); scrub = null },
                    modifier = Modifier.height(28.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(progress.positionMs / 1000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = viewModel::previous) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                    IconButton(onClick = viewModel::togglePlay, modifier = Modifier.popOnChange(progress.playing, peak = 1.15f)) {
                        Icon(if (progress.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = viewModel::next) { Icon(Icons.Rounded.SkipNext, "Next") }
                    Spacer(Modifier.weight(1f))
                    Text(formatDuration(progress.durationMs / 1000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}


/** Equalizer preset + volume boost for the Audio library (same controls as the player settings). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SoundSheet(controller: com.youtubly.app.playback.PlayerController, onDismiss: () -> Unit) {
    val eq by controller.eqPreset.collectAsStateWithLifecycle()
    val boost by controller.boostDb.collectAsStateWithLifecycle()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
            Text("Sound", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text("Equalizer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.youtubly.app.domain.audio.EqPreset.entries.forEach { p ->
                    FilterChip(selected = eq == p, onClick = { controller.setEqPreset(p) }, label = { Text(p.label) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Volume boost", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.youtubly.app.playback.VolumeBoost.LEVELS_DB.forEach { db ->
                    FilterChip(selected = boost == db, onClick = { controller.setBoostDb(db) }, label = { Text(if (db == 0) "Off" else "+$db dB") })
                }
            }
        }
    }
}
