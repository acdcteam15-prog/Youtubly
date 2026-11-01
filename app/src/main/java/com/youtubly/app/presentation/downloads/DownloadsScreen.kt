package com.youtubly.app.presentation.downloads

import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Link
import com.youtubly.app.presentation.blur.BlurredAsyncImage

import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.domain.model.DownloadStatus
import com.youtubly.app.presentation.components.EmptyState
import com.youtubly.app.presentation.components.GradientTitle
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.components.shimmer
import com.youtubly.app.presentation.components.staggeredEntrance
import com.youtubly.app.presentation.theme.BrandGradientH

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    onOpen: (String) -> Unit,
    onOpenEditor: () -> Unit = {},
    onOpenAudio: () -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val autoDelete by viewModel.autoDelete.collectAsStateWithLifecycle()
    val transfer by viewModel.transfer.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val rulesHint by viewModel.rulesHint.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val cleanerGroups by viewModel.cleanerGroups.collectAsStateWithLifecycle()
    var showCleaner by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showCleaner) StorageCleanerSheet(cleanerGroups, onDelete = viewModel::deleteMany, onDismiss = { showCleaner = false })
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.events.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                GradientTitle("Saved", style = MaterialTheme.typography.headlineMedium)
                Text("Swipe left to delete · long-press for more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.FilledTonalIconButton(onClick = onOpenAudio) {
                Icon(Icons.Rounded.Headphones, "Audio library")
            }
        }
        AnimatedVisibility(summary != null) {
            summary?.let { TransferStrip(it) }
        }
        if (downloads?.isNotEmpty() == true) {
            StorageCard(
                storage = storage,
                watchedCount = downloads.orEmpty().count { it.status == DownloadStatus.COMPLETED && it.watchedPercent >= 95 },
                autoDelete = autoDelete,
                onDeleteWatched = viewModel::deleteWatched,
                onAutoDelete = viewModel::setAutoDelete,
                onCleanUp = { showCleaner = true }
            )
            androidx.compose.foundation.layout.Row(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                com.youtubly.app.domain.download.SavedSort.entries.forEach { o ->
                    androidx.compose.material3.FilterChip(
                        selected = sort == o, onClick = { viewModel.setSort(o) },
                        label = { Text(o.label) }, shape = MaterialTheme.shapes.extraLarge
                    )
                }
            }
        }
        AnimatedContent(
            targetState = downloads,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { it == null || it.isEmpty() },
            label = "downloads"
        ) { list ->
            when {
                list == null -> SkeletonFeed(count = 3)
                list.isEmpty() -> EmptyState(Icons.Rounded.DownloadForOffline, "No downloads yet", "Save videos or audio from the player to watch offline.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp + com.youtubly.app.presentation.components.miniBarClearance()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(list, key = { _, it -> it.id }) { i, item ->
                        Box(Modifier.animateItem().staggeredEntrance(i)) {
                            SwipeRow(
                                item,
                                stats = transfer[item.id],
                                queuedHint = rulesHint,
                                onDelete = { viewModel.delete(item.id) },
                                onPause = { viewModel.pause(item.id) },
                                onResume = { viewModel.resume(item.id) },
                                onEdit = { viewModel.sendToEditor(item.id); onOpenEditor() }
                            ) {
                                item.filePath?.let { onOpen(item.id) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(item: DownloadItem, stats: TransferStats? = null, queuedHint: String? = null, onDelete: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onEdit: () -> Unit, onOpen: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val bg by animateColorAsState(
                if (state.targetValue == SwipeToDismissBoxValue.EndToStart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                label = "swipeBg"
            )
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(bg).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) { Icon(Icons.Rounded.Delete, "Delete", tint = Color.White) }
        }
    ) {
        DownloadCard(item, stats, onPause, onResume, onOpen, onEdit = onEdit, onDelete = onDelete, queuedHint = queuedHint)
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    stats: TransferStats?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    queuedHint: String? = null
) {
    val progress by animateFloatAsState(item.progress / 100f, tween(500), label = "progress")
    var menuOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val canEdit = item.status == DownloadStatus.COMPLETED && item.filePath != null
    Box {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .bounceClick(
                scaleDown = 0.98f,
                onLongClick = { menuOpen = true },
                onClick = { if (item.status == DownloadStatus.COMPLETED) onOpen() }
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(112.dp).height(64.dp).clip(RoundedCornerShape(12.dp)).shimmer()) {
            BlurredAsyncImage(item.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (item.isAudioOnly) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(4.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp)
                ) { Icon(Icons.Rounded.Headphones, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
            }
            // Where you stopped (thin bar on the thumbnail, like YouTube's red line).
            if (item.status == DownloadStatus.COMPLETED && item.resumeFraction > 0.01f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth(item.resumeFraction).height(3.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                item.qualityLabel,
                when {
                    item.status == DownloadStatus.COMPLETED && item.totalBytes > 0 -> formatBytes(item.totalBytes)
                    item.totalBytes > 0 -> "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
                    else -> null
                },
                item.watchedPercent.takeIf { item.status == DownloadStatus.COMPLETED && it >= 95 }?.let { "Watched" }
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            AnimatedContent(targetState = item.status, label = "status", transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() }) { status ->
                when (status) {
                    DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED -> Column {
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                            Box(Modifier.fillMaxWidth(progress).height(5.dp).clip(CircleShape).background(BrandGradientH))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (status) {
                                DownloadStatus.QUEUED -> queuedHint ?: "Queued"
                                DownloadStatus.PAUSED -> "Paused · ${item.progress}%"
                                else -> listOfNotNull(
                                    "${item.progress}%",
                                    stats?.let { "${formatBytes(it.bytesPerSecond)}/s" },
                                    stats?.etaSeconds?.let { formatEta(it) }
                                ).joinToString("  ·  ")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DownloadStatus.COMPLETED -> StatusLabel(Icons.Rounded.CheckCircle, "Ready offline", MaterialTheme.colorScheme.tertiary)
                    DownloadStatus.FAILED -> StatusLabel(Icons.Rounded.ErrorOutline, "Failed", MaterialTheme.colorScheme.error)
                }
            }
        }
        AnimatedContent(
            targetState = item.status,
            transitionSpec = {
                (scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f), initialScale = 0.3f) + fadeIn()) togetherWith
                    (scaleOut(targetScale = 0.3f) + fadeOut())
            },
            label = "trailing"
        ) { st -> when (st) {
            DownloadStatus.COMPLETED -> Box(Modifier.padding(start = 8.dp).size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Open", tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            DownloadStatus.QUEUED, DownloadStatus.RUNNING ->
                // Filling ring around the pause button.
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                    androidx.compose.material3.IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, "Pause", modifier = Modifier.size(20.dp)) }
                }
            DownloadStatus.PAUSED ->
                androidx.compose.material3.IconButton(onClick = onResume) { Icon(Icons.Rounded.PlayCircle, "Resume") }
            DownloadStatus.FAILED ->
                androidx.compose.material3.IconButton(onClick = onResume) { Icon(Icons.Rounded.Refresh, "Retry") }
        } }
    }
    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text(if (item.isAudioOnly) "Edit audio" else "Edit") },
            leadingIcon = { Icon(Icons.Rounded.ContentCut, null) },
            enabled = canEdit,
            onClick = { menuOpen = false; onEdit() }
        )
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Share file") },
            leadingIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Share, null) },
            enabled = canEdit,
            onClick = { menuOpen = false; item.filePath?.let { com.youtubly.app.presentation.components.shareFile(ctx, it, item.isAudioOnly, item.title) } }
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Share link") },
            leadingIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Link, null) },
            onClick = { menuOpen = false; com.youtubly.app.presentation.components.shareText(ctx, "https://youtu.be/${item.videoId}", item.title, "Share video") }
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Delete") },
            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
            onClick = { menuOpen = false; onDelete() }
        )
    }
    }
}

@Composable
private fun StorageCard(
    storage: com.youtubly.app.domain.model.DownloadStorage?,
    watchedCount: Int,
    autoDelete: Boolean,
    onDeleteWatched: () -> Unit,
    onAutoDelete: (Boolean) -> Unit,
    onCleanUp: () -> Unit = {}
) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp)
    ) {
        val used = storage?.usedBytes ?: 0L
        val free = storage?.freeBytes ?: 0L
        Text(
            if (storage == null) "Measuring storage…" else "Using ${formatBytes(used)}  ·  ${formatBytes(free)} free",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        val fraction = if (used + free > 0) used.toFloat() / (used + free) else 0f
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            // At least a sliver, so a few MB on a big phone is still visible.
            Box(Modifier.fillMaxWidth(if (used > 0) fraction.coerceIn(0.01f, 1f) else 0f).height(6.dp).clip(CircleShape).background(BrandGradientH))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-delete watched", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("Once 95% watched", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = autoDelete, onCheckedChange = onAutoDelete)
        }
        Row {
            androidx.compose.material3.TextButton(onClick = onDeleteWatched, enabled = watchedCount > 0) {
                Icon(Icons.Rounded.Delete, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (watchedCount > 0) "Delete watched ($watchedCount)" else "Delete watched")
            }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = onCleanUp) {
                Icon(Icons.Rounded.CleaningServices, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Free up space")
            }
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/** "~45 s left", "~3 min left", "~1 h 20 min left". */
fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "~${seconds.coerceAtLeast(1)} s left"
    seconds < 3600 -> "~${(seconds + 30) / 60} min left"
    else -> "~${seconds / 3600} h ${(seconds % 3600) / 60} min left"
}

/** Header strip while anything is downloading: count, combined speed, time to finish. */
@Composable
private fun TransferStrip(s: TransferSummary) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Downloading, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            listOfNotNull(
                "${s.active} downloading",
                "${formatBytes(s.bytesPerSecond)}/s",
                s.etaSeconds?.let { formatEta(it) }
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
