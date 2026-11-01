package com.youtubly.app.presentation.channel

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.core.SearchText
import com.youtubly.app.domain.channel.PlaylistProgress
import com.youtubly.app.domain.model.RemotePlaylist
import com.youtubly.app.domain.model.Video
import com.youtubly.app.download.PlaylistDownloader
import com.youtubly.app.presentation.blur.BlurredAsyncImage
import com.youtubly.app.presentation.components.ErrorView
import com.youtubly.app.presentation.components.LocalWatchProgress
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.VideoCard
import com.youtubly.app.presentation.components.adaptiveItems
import com.youtubly.app.presentation.components.formatCount
import com.youtubly.app.presentation.components.miniBarClearance
import com.youtubly.app.presentation.components.rememberFeedColumns

/**
 * A YouTube playlist: header with progress, Continue / Play all / Shuffle, Save to library, Download all,
 * search inside it, and its videos (more load as you scroll).
 */
@Composable
fun RemotePlaylistScreen(
    playlistUrl: String,
    onBack: () -> Unit,
    onPlay: (Video) -> Unit,
    onOpenChannel: (String) -> Unit,
    viewModel: RemotePlaylistViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(playlistUrl) { viewModel.load(playlistUrl) }
    LaunchedEffect(Unit) { viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    androidx.activity.compose.BackHandler(enabled = ui.searchOpen) { viewModel.closeSearch() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val p = ui.playlist
        when {
            ui.loading -> Column(Modifier.statusBarsPadding().padding(top = 56.dp)) { SkeletonFeed() }
            ui.error != null -> ErrorView(ui.error!!, onRetry = { viewModel.load(playlistUrl, force = true) })
            p != null -> PlaylistContent(
                playlist = p,
                ui = ui,
                downloadProgress = downloadProgress,
                viewModel = viewModel,
                onPlay = onPlay,
                onOpenChannel = onOpenChannel
            )
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
        ) { Icon(Icons.Rounded.ArrowBack, "Back", tint = Color.White) }
        if (ui.playlist != null) {
            IconButton(
                onClick = { if (ui.searchOpen) viewModel.closeSearch() else viewModel.openSearch() },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
            ) {
                Icon(if (ui.searchOpen) Icons.Rounded.Close else Icons.Rounded.Search, if (ui.searchOpen) "Close search" else "Search this playlist", tint = Color.White)
            }
        }
    }
}

@Composable
private fun PlaylistContent(
    playlist: RemotePlaylist,
    ui: RemotePlaylistUi,
    downloadProgress: PlaylistDownloader.Progress?,
    viewModel: RemotePlaylistViewModel,
    onPlay: (Video) -> Unit,
    onOpenChannel: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val searching = ui.searchOpen && ui.query.isNotBlank()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, ui.next) { if (nearEnd && ui.next != null) viewModel.loadMore() }
    val cols = rememberFeedColumns()
    var showDownload by rememberSaveable { mutableStateOf(false) }
    var showSave by rememberSaveable { mutableStateOf(false) }

    // Watch progress for this playlist (videos loaded so far).
    val progressMap = LocalWatchProgress.current.value
    val ids = remember(ui.videos) { ui.videos.map { it.id } }
    val watched = PlaylistProgress.watchedCount(ids, progressMap)
    val continueIdx = PlaylistProgress.continueIndex(ids, progressMap)
    val total = maxOf(playlist.videoCount.toInt(), ids.size)

    val shown = remember(ui.videos, ui.query, ui.searchOpen) {
        if (!ui.searchOpen || ui.query.isBlank()) ui.videos
        else ui.videos.filter { SearchText.matches(it.title, ui.query) || SearchText.matches(it.uploaderName, ui.query) }
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp + miniBarClearance())) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 8.dp)) {
                if (ui.searchOpen) {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    OutlinedTextField(
                        value = ui.query,
                        onValueChange = viewModel::setQuery,
                        singleLine = true,
                        placeholder = { Text("Search ${playlist.title}", maxLines = 1) },
                        shape = MaterialTheme.shapes.extraLarge,
                        trailingIcon = { if (ui.next != null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).focusRequester(focus)
                    )
                }
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (playlist.thumbnailUrl != null) {
                        BlurredAsyncImage(
                            model = playlist.thumbnailUrl,
                            contentDescription = null,
                            channel = com.youtubly.app.blur.channelKeyOf(playlist.uploaderUrl),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Rounded.PlaylistPlay, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(playlist.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                val owner = playlist.uploaderName?.takeIf { it.isNotBlank() }
                if (owner != null) {
                    Text(
                        owner,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.then(
                            if (playlist.uploaderUrl != null) Modifier.clickable { onOpenChannel(playlist.uploaderUrl) } else Modifier
                        )
                    )
                }
                if (total > 0) {
                    Text(
                        buildString {
                            append("${formatCount(total.toLong())} videos")
                            if (watched > 0) append("  ·  ${if (watched >= total) "all" else watched.toString() + " of " + total} watched")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (watched > 0) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (watched.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (continueIdx != null) {
                    val next = ui.videos[continueIdx]
                    FilledTonalButton(
                        onClick = { viewModel.startQueue(shuffle = false, startAt = next, onReady = onPlay) },
                        enabled = !ui.preparing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Continue · ${next.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.startQueue(shuffle = false, onReady = onPlay) },
                        enabled = ui.videos.isNotEmpty() && !ui.preparing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (ui.preparing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp)); Text(if (ui.preparing) "Loading…" else "Play all")
                    }
                    OutlinedButton(
                        onClick = { viewModel.startQueue(shuffle = true, onReady = onPlay) },
                        enabled = ui.videos.size > 1 && !ui.preparing,
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showSave = true }, enabled = ui.videos.isNotEmpty() && !ui.preparing, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("Save")
                    }
                    OutlinedButton(onClick = { showDownload = true }, enabled = ui.videos.isNotEmpty() && !ui.preparing, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Download")
                    }
                }
                if (downloadProgress != null) {
                    Spacer(Modifier.height(10.dp))
                    DownloadStatusRow(downloadProgress, onCancel = viewModel::cancelDownloads)
                }
            }
        }
        if (searching && shown.isEmpty()) {
            item {
                Text(
                    if (ui.next != null) "No match yet — still loading the rest of the playlist…" else "No videos match \"${ui.query}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                )
            }
        }
        adaptiveItems(shown, cols, key = { i, v -> "${v.id}_$i" }) { i, v ->
            VideoCard(
                v,
                onClick = { viewModel.startQueue(shuffle = false, startAt = v, onReady = onPlay) },
                index = i, compact = cols > 1, showBlockedChannel = true
            )
        }
        if (ui.loadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }
    }

    if (showSave) {
        var autoRemove by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save to your playlists") },
            text = {
                Column {
                    Text(
                        "Copies all ${total} videos into a playlist on this device.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    com.youtubly.app.presentation.playlists.AutoRemoveRow(autoRemove, { autoRemove = it })
                }
            },
            confirmButton = { TextButton(onClick = { showSave = false; viewModel.saveToLibrary(autoRemove) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } }
        )
    }
    if (showDownload) {
        var choice by rememberSaveable { mutableStateOf(PlaylistDownloader.Quality.P720.name) }
        AlertDialog(
            onDismissRequest = { showDownload = false },
            title = { Text("Download this playlist") },
            text = {
                Column {
                    Text(
                        "Every video is queued at this quality and follows your download rules (Wi-Fi only, battery, overnight). Videos you already saved are skipped.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    PlaylistDownloader.Quality.entries.forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().clickable { choice = q.name },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = choice == q.name, onClick = { choice = q.name })
                            Text(q.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDownload = false
                    viewModel.downloadAll(PlaylistDownloader.Quality.valueOf(choice))
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { showDownload = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DownloadStatusRow(p: PlaylistDownloader.Progress, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Queuing downloads ${p.handled} / ${p.total}", style = MaterialTheme.typography.labelLarge)
                p.current?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onCancel) { Text("Stop") }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (p.total == 0) 0f else p.handled.toFloat() / p.total },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}
