package com.youtubly.app.presentation.history

import com.youtubly.app.presentation.blur.BlurredAsyncImage

import com.youtubly.app.presentation.components.adaptiveItems
import com.youtubly.app.presentation.components.rememberFeedColumns
import androidx.compose.animation.animateColorAsState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.youtubly.app.domain.model.HistoryEntry
import com.youtubly.app.domain.model.Video
import com.youtubly.app.presentation.components.EmptyState
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.components.formatDuration
import com.youtubly.app.presentation.components.shimmer
import com.youtubly.app.presentation.components.thumbnailFor
import com.youtubly.app.presentation.components.WatchProgressOverlay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onVideoClick: (Video) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val all by viewModel.all.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val selecting by viewModel.selecting.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var olderDialog by remember { mutableStateOf(false) }
    var watchedDialog by remember { mutableStateOf(false) }
    var channelSheet by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.results.collect { r ->
            if (snackbar.showSnackbar(r.message, actionLabel = "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                viewModel.undo(r.restore)
            }
        }
    }
    // Back leaves multi-select before it leaves the screen.
    BackHandler(enabled = selecting) { viewModel.endSelection() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        if (selecting) {
            val visibleIds = days.orEmpty().flatMap { d -> d.entries.map { it.video.id } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::endSelection) { Icon(Icons.Rounded.Close, "Cancel selection") }
                Text(
                    if (selection.isEmpty()) "Select videos" else "${selection.size} selected",
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.selectAll(visibleIds) }, enabled = visibleIds.any { it !in selection }) {
                    Icon(Icons.Rounded.SelectAll, "Select all")
                }
                IconButton(onClick = viewModel::removeSelected, enabled = selection.isNotEmpty()) {
                    Icon(Icons.Rounded.Delete, "Remove selected")
                }
            }
        } else Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("History", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = all.isNotEmpty()) { Icon(Icons.Rounded.MoreVert, "Cleanup options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Select videos") },
                        leadingIcon = { Icon(Icons.Rounded.Checklist, null) },
                        onClick = { menuOpen = false; viewModel.startSelection() }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove older than…") },
                        leadingIcon = { Icon(Icons.Rounded.EventBusy, null) },
                        onClick = { menuOpen = false; olderDialog = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove finished videos") },
                        leadingIcon = { Icon(Icons.Rounded.TaskAlt, null) },
                        onClick = { menuOpen = false; watchedDialog = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove a channel…") },
                        leadingIcon = { Icon(Icons.Rounded.PersonRemove, null) },
                        onClick = { menuOpen = false; channelSheet = true }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Clear all history") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                        onClick = { menuOpen = false; confirmClear = true }
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            placeholder = { Text("Search history") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") }
            },
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.PauseCircle, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Pause history", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (paused) "New videos aren't being recorded" else "Swipe left to remove · long-press to select",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = paused, onCheckedChange = viewModel::setPaused)
        }

        val list = days
        when {
            list == null -> SkeletonFeed(count = 3)
            list.isEmpty() -> EmptyState(
                Icons.Rounded.History,
                if (query.isBlank()) "No watch history" else "No matches",
                if (query.isBlank()) "Videos you watch show up here." else "Nothing in your history matches \"$query\"."
            )
            else -> { val cols = rememberFeedColumns(minCell = 360.dp); LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
                list.forEach { day ->
                    stickyHeader(key = "h_${day.label}") {
                        Text(
                            day.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 10.dp)
                        )
                    }
                    adaptiveItems(day.entries, cols, key = { _, e -> e.video.id }) { _, entry ->
                        // animateItem only works on an item's root, i.e. in the one-column layout.
                        Box((if (cols == 1) Modifier.animateItem() else Modifier.padding(horizontal = 4.dp)).padding(bottom = 8.dp)) {
                            val id = entry.video.id
                            if (selecting) {
                                HistoryRow(
                                    entry,
                                    selected = id in selection,
                                    onLongClick = { viewModel.toggleSelected(id) }
                                ) { viewModel.toggleSelected(id) }
                            } else SwipeToRemove(onRemove = { viewModel.remove(id) }) {
                                HistoryRow(entry, onLongClick = { viewModel.startSelection(id) }) { onVideoClick(entry.video) }
                            }
                        }
                    }
                }
            } }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This removes every video from your watch history and resets resume positions.") },
            confirmButton = { TextButton(onClick = { viewModel.clearAll(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }

    if (olderDialog) OlderThanDialog(
        countFor = viewModel::countOlderThan,
        onConfirm = { d -> viewModel.removeOlderThan(d); olderDialog = false },
        onDismiss = { olderDialog = false }
    )

    if (watchedDialog) {
        val n = remember(all) { viewModel.countWatched() }
        AlertDialog(
            onDismissRequest = { watchedDialog = false },
            title = { Text("Remove finished videos?") },
            text = { Text(if (n == 0) "No videos are marked as finished yet." else "Removes $n video${if (n == 1) "" else "s"} you watched to the end. You can undo this.") },
            confirmButton = { TextButton(enabled = n > 0, onClick = { viewModel.removeWatched(); watchedDialog = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { watchedDialog = false }) { Text("Cancel") } }
        )
    }

    if (channelSheet) ChannelPickerSheet(
        channels = channels,
        onPick = { name -> viewModel.removeChannel(name); channelSheet = false },
        onDismiss = { channelSheet = false }
    )

    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
    }
}

private val OLDER_THAN = listOf(7 to "1 week", 30 to "1 month", 90 to "3 months", 365 to "1 year")

@Composable
private fun OlderThanDialog(countFor: (Int) -> Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(30) }
    val n = remember(days) { countFor(days) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove older entries") },
        text = {
            Column {
                OLDER_THAN.forEach { (d, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { days = d }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = days == d, onClick = { days = d })
                        Text("Older than $label", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (n == 0) "Nothing that old in your history." else "$n video${if (n == 1) "" else "s"} will be removed. You can undo this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(enabled = n > 0, onClick = { onConfirm(days) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelPickerSheet(channels: List<HistoryChannel>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Remove a channel from history", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                placeholder = { Text("Find a channel") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            )
        }
        val shown = remember(channels, filter) { channels.filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) } }
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 32.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
            items(shown, key = { it.name }) { ch ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onPick(ch.name) }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(ch.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${ch.count}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Rounded.Delete, "Remove ${ch.name}", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) { onRemove(); true } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val bg by animateColorAsState(
                if (state.targetValue == SwipeToDismissBoxValue.EndToStart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                label = "historySwipe"
            )
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(bg).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) { Icon(Icons.Rounded.Delete, "Remove", tint = Color.White) }
        }
    ) { content() }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val v = entry.video
    val bg by animateColorAsState(
        if (selected == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "historyRowBg"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(bg)
            .bounceClick(scaleDown = 0.98f, onLongClick = onLongClick, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected != null) {
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(end = 6.dp))
        }
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).shimmer()) {
            BlurredAsyncImage(thumbnailFor(v.thumbnailUrl), null, channel = com.youtubly.app.blur.channelKeyOf(v.uploaderUrl), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
            WatchProgressOverlay(entry.progress)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(v.uploaderName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
