package com.youtubly.app.presentation.playlists

import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Link
import com.youtubly.app.presentation.blur.BlurredAsyncImage

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.playlist.PlaylistOrder
import com.youtubly.app.presentation.components.EmptyState
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.components.formatDuration
import com.youtubly.app.presentation.components.shimmer
import com.youtubly.app.presentation.components.thumbnailFor
import com.youtubly.app.presentation.components.WatchProgressOverlay
import com.youtubly.app.presentation.components.watchProgressOf
import kotlinx.coroutines.launch

private const val HEADER_KEY = "__header__"

/**
 * One playlist: Play all / Shuffle, tap a video to play from there, drag the handle to
 * reorder, swipe left to remove (with Undo), rename and delete from the menu.
 */
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onPlay: (Video) -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel()
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    var showFinished by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val shareContext = androidx.compose.ui.platform.LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Local copy of the order while dragging; synced from the database otherwise.
    var order by remember { mutableStateOf<List<Video>>(emptyList()) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(videos) { if (dragKey == null) order = videos.orEmpty() }
    val listState = rememberLazyListState()

    fun onDrag(dy: Float) {
        dragOffset += dy
        val key = dragKey ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val cur = visible.firstOrNull { it.key == key } ?: return
        val center = cur.offset + dragOffset + cur.size / 2f
        val target = visible.firstOrNull {
            it.key != key && it.key != HEADER_KEY && center > it.offset && center < it.offset + it.size
        } ?: return
        val from = order.indexOfFirst { it.id == key }
        val to = order.indexOfFirst { it.id == target.key }
        if (from < 0 || to < 0) return
        order = PlaylistOrder.move(order, from, to)
        // Keep the dragged row under the finger now that its slot moved.
        dragOffset += (cur.offset - target.offset).toFloat()
    }
    fun endDrag() {
        if (dragKey != null && order.map { it.id } != videos.orEmpty().map { it.id }) viewModel.reorder(order.map { it.id })
        dragKey = null
        dragOffset = 0f
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Share playlist") },
                            leadingIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Share, null) },
                            enabled = !videos.isNullOrEmpty(),
                            onClick = {
                                menu = false
                                val list = videos.orEmpty()
                                com.youtubly.app.presentation.components.shareText(
                                    shareContext,
                                    com.youtubly.app.core.ShareLinks.playlistText(playlist?.name ?: "Playlist", list.map { it.title to it.url }),
                                    playlist?.name, "Share playlist"
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; renaming = true }
                        )
                        if (playlist?.finishedAt == null) {
                            DropdownMenuItem(
                                text = { Text("Remove videos as I finish them") },
                                leadingIcon = { Checkbox(checked = playlist?.autoRemoveFinished == true, onCheckedChange = null) },
                                onClick = { menu = false; viewModel.setAutoRemove(playlist?.autoRemoveFinished != true) }
                            )
                        }
                        if (finished.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Restart playlist") },
                                leadingIcon = { Icon(Icons.Rounded.Restore, null) },
                                onClick = { menu = false; confirmRestart = true }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete playlist") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menu = false; confirmDelete = true }
                        )
                    }
                }
            }

            val list = videos
            if (list == null) {
                SkeletonFeed(count = 3)
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp + com.youtubly.app.presentation.components.miniBarClearance()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = HEADER_KEY) {
                        // Continue: the video you stopped in the middle of; else the first one not finished (only once you've started the list).
                        val progress = com.youtubly.app.presentation.components.LocalWatchProgress.current.value
                        val started = order.any { (progress[it.id] ?: 0f) > 0.02f }
                        val continueFrom = if (!started) null
                        else order.firstOrNull { (progress[it.id] ?: 0f) in 0.02f..0.9f }
                            ?: order.firstOrNull { (progress[it.id] ?: 0f) < 0.9f }
                        Header(
                            name = playlist?.name.orEmpty(),
                            videos = order.ifEmpty { finished },
                            onPlayAll = { viewModel.startQueue(shuffle = false)?.let(onPlay) },
                            onShuffle = { viewModel.startQueue(shuffle = true)?.let(onPlay) },
                            continueTitle = continueFrom?.title,
                            onContinue = { continueFrom?.let { v -> viewModel.startQueue(shuffle = false, startAt = v)?.let(onPlay) } }
                        )
                    }
                    if (order.isEmpty()) {
                        item(key = "empty") {
                            if (finished.isNotEmpty()) {
                                EmptyState(Icons.Rounded.TaskAlt, "All ${finished.size} videos finished", "Restart the playlist from the menu to watch it again.")
                            } else {
                                EmptyState(Icons.Rounded.PlaylistPlay, "This playlist is empty", "Long-press any video → \"Save to playlist…\".")
                            }
                        }
                    }
                    items(order, key = { it.id }) { v ->
                        val dragging = dragKey == v.id
                        val rowMod = if (dragging) {
                            Modifier.zIndex(1f).graphicsLayer { translationY = dragOffset }.shadow(8.dp, MaterialTheme.shapes.large)
                        } else Modifier.animateItem()
                        Box(rowMod) {
                            SwipeToRemove(
                                enabled = dragKey == null,
                                onRemove = {
                                    val index = order.indexOfFirst { it.id == v.id }
                                    viewModel.remove(v)
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        val r = snackbar.showSnackbar("Removed from playlist", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                        if (r == SnackbarResult.ActionPerformed) viewModel.undoRemove(v, index)
                                    }
                                }
                            ) {
                                PlaylistRow(
                                    v,
                                    onClick = { viewModel.startQueue(shuffle = false, startAt = v)?.let(onPlay) },
                                    handle = Modifier.pointerInput(v.id) {
                                        detectVerticalDragGestures(
                                            onDragStart = { dragKey = v.id; dragOffset = 0f },
                                            onDragEnd = { endDrag() },
                                            onDragCancel = { endDrag() }
                                        ) { change, dy -> change.consume(); onDrag(dy) }
                                    }
                                )
                            }
                        }
                    }
                    if (finished.isNotEmpty()) {
                        item(key = "finished_header") {
                            Row(
                                Modifier.fillMaxWidth().clickable { showFinished = !showFinished }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.TaskAlt, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Finished (${finished.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Icon(if (showFinished) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (showFinished) "Hide" else "Show")
                            }
                        }
                        if (showFinished) {
                            items(finished, key = { "fin_${it.id}" }) { v ->
                                FinishedRow(
                                    v,
                                    onPlay = { if (order.isEmpty()) viewModel.startQueue(shuffle = false, startAt = v)?.let(onPlay) else onPlay(v) },
                                    onRestore = { viewModel.restore(v) }
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
    }

    if (renaming) {
        NameDialog("Rename playlist", playlist?.name.orEmpty(), "Save", onConfirm = { viewModel.rename(it); renaming = false }, onDismiss = { renaming = false })
    }
    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("Restart \"${playlist?.name.orEmpty()}\"?") },
            text = { Text("All ${finished.size} finished videos come back to the list and are marked unwatched.") },
            confirmButton = { TextButton(onClick = { confirmRestart = false; viewModel.restart() }) { Text("Restart") } },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Cancel") } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${playlist?.name.orEmpty()}\"?") },
            text = { Text("The playlist is removed from this device. The videos themselves aren't affected.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Header(name: String, videos: List<Video>, onPlayAll: () -> Unit, onShuffle: () -> Unit, continueTitle: String? = null, onContinue: () -> Unit = {}) {
    val cover = videos.firstOrNull()?.thumbnailUrl
    val totalSec = videos.sumOf { it.duration.coerceAtLeast(0) }
    Column(Modifier.padding(bottom = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) BlurredAsyncImage(thumbnailFor(cover), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Rounded.PlaylistPlay, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(name, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(
            listOfNotNull(
                if (videos.size == 1) "1 video" else "${videos.size} videos",
                totalSec.takeIf { it > 0 }?.let { formatDuration(it) }
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        if (continueTitle != null) {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp))
                Text("Continue: $continueTitle", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPlayAll,
                enabled = videos.isNotEmpty(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play all")
            }
            OutlinedButton(onClick = onShuffle, enabled = videos.size > 1, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
            }
        }
    }
}

@Composable
private fun PlaylistRow(v: Video, onClick: () -> Unit, handle: Modifier) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .bounceClick(scaleDown = 0.98f, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).shimmer()) {
            BlurredAsyncImage(thumbnailFor(v.thumbnailUrl), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            WatchProgressOverlay(watchProgressOf(v.id))
            if (v.duration > 0) {
                Text(
                    formatDuration(v.duration),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(v.uploaderName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(
            Icons.Rounded.DragHandle,
            "Drag to reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = handle.padding(8.dp).size(24.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(enabled: Boolean, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) { onRemove(); true } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            val bg by animateColorAsState(
                if (state.targetValue == SwipeToDismissBoxValue.EndToStart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                label = "playlistSwipe"
            )
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(bg).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) { Icon(Icons.Rounded.Delete, "Remove", tint = Color.White) }
        }
    ) { content() }
}

/** A video that was finished and moved out of the list: tap to play it again, or bring it back (marked unwatched). */
@Composable
private fun FinishedRow(v: Video, onPlay: () -> Unit, onRestore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .bounceClick(scaleDown = 0.98f, onClick = onPlay)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(96.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).shimmer()) {
            BlurredAsyncImage(thumbnailFor(v.thumbnailUrl), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(v.uploaderName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRestore) { Icon(Icons.Rounded.Restore, "Put back in the list (unwatched)") }
    }
}
