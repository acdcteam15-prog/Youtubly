package com.youtubly.app.presentation.playlists

import com.youtubly.app.presentation.blur.BlurredAsyncImage

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.youtubly.app.data.local.entity.PlaylistSummary
import com.youtubly.app.presentation.components.EmptyState
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.components.shimmer
import com.youtubly.app.presentation.components.thumbnailFor

/** You → Playlists: grid of local playlists with cover and count; create, export, import. */
@Composable
fun PlaylistsScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenYoutubePlaylist: (String) -> Unit = {},
    /** False when Playlists is a bottom-bar tab (nothing to go back to). */
    showBack: Boolean = true,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistSummary?>(null) }
    var deleting by remember { mutableStateOf<PlaylistSummary?>(null) }
    var restarting by remember { mutableStateOf<PlaylistSummary?>(null) }
    var clearingShelf by remember { mutableStateOf(false) }
    var shelfOpen by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::export)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            else Spacer(Modifier.width(16.dp))
            Text("Playlists", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { addMenu = true }) { Icon(Icons.Rounded.Add, "Add playlist") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("New playlist") },
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                        onClick = { addMenu = false; creating = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Add from YouTube link…") },
                        leadingIcon = { Icon(Icons.Rounded.Link, null) },
                        onClick = { addMenu = false; linking = true }
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Export playlists") },
                        leadingIcon = { Icon(Icons.Rounded.FileUpload, null) },
                        enabled = !playlists.isNullOrEmpty(),
                        onClick = { menu = false; exportLauncher.launch("youtubly-playlists.json") }
                    )
                    DropdownMenuItem(
                        text = { Text("Import playlists") },
                        leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                        onClick = { menu = false; importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                    )
                }
            }
        }

        val list = playlists
        when {
            list == null -> SkeletonFeed(count = 2)
            list.isEmpty() -> EmptyState(
                Icons.Rounded.PlaylistPlay,
                "No playlists yet",
                "Long-press any video → \"Save to playlist…\", or tap + to create one."
            )
            else -> {
                val active = list.filter { !it.isFinished }
                val finished = list.filter { it.isFinished }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp + com.youtubly.app.presentation.components.miniBarClearance()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(active, key = { it.id }) { p ->
                        PlaylistTile(
                            p,
                            onClick = { onOpen(p.id) },
                            onRename = { renaming = p },
                            onDelete = { deleting = p },
                            onToggleAutoRemove = { viewModel.setAutoRemove(p.id, !p.autoRemoveFinished) },
                            onRestart = { restarting = p }
                        )
                    }
                    if (finished.isNotEmpty()) {
                        // The Finished shelf: auto-remove playlists whose last video was watched.
                        item(span = { GridItemSpan(maxLineSpan) }, key = "shelf_header") {
                            Row(
                                Modifier.fillMaxWidth().clickable { shelfOpen = !shelfOpen }.padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.TaskAlt, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(8.dp))
                                Text("Finished (${finished.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (shelfOpen) TextButton(onClick = { clearingShelf = true }) { Text("Clear") }
                                Icon(if (shelfOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (shelfOpen) "Hide" else "Show")
                            }
                        }
                        if (shelfOpen) {
                            items(finished, key = { "done_${it.id}" }) { p ->
                                PlaylistTile(
                                    p,
                                    onClick = { onOpen(p.id) },
                                    onRename = { renaming = p },
                                    onDelete = { deleting = p },
                                    onToggleAutoRemove = {},
                                    onRestart = { restarting = p }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        var autoRemove by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New playlist") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, singleLine = true, label = { Text("Name") })
                    Spacer(Modifier.height(8.dp))
                    AutoRemoveRow(autoRemove, { autoRemove = it })
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.create(name.trim(), autoRemove); creating = false }, enabled = name.isNotBlank()) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } }
        )
    }
    if (linking) {
        YoutubePlaylistLinkDialog(
            onOpen = { url -> linking = false; onOpenYoutubePlaylist(url) },
            onDismiss = { linking = false }
        )
    }
    renaming?.let { p ->
        NameDialog("Rename playlist", p.name, "Save", onConfirm = { viewModel.rename(p.id, it); renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("The playlist is removed from this device. The videos themselves aren't affected.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(p.id, p.name); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
    restarting?.let { p ->
        AlertDialog(
            onDismissRequest = { restarting = null },
            title = { Text("Restart \"${p.name}\"?") },
            text = { Text("All ${p.finishedCount} finished videos come back to the list and are marked unwatched.") },
            confirmButton = { TextButton(onClick = { viewModel.restart(p.id); restarting = null }) { Text("Restart") } },
            dismissButton = { TextButton(onClick = { restarting = null }) { Text("Cancel") } }
        )
    }
    if (clearingShelf) {
        val shelf = playlists.orEmpty().filter { it.isFinished }
        AlertDialog(
            onDismissRequest = { clearingShelf = false },
            title = { Text("Clear the Finished shelf?") },
            text = { Text("Deletes ${shelf.size} finished playlist${if (shelf.size == 1) "" else "s"} from this device. The videos themselves aren't affected.") },
            confirmButton = { TextButton(onClick = { viewModel.clearFinished(shelf.map { it.id }); clearingShelf = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearingShelf = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTile(
    p: PlaylistSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleAutoRemove: () -> Unit,
    onRestart: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true })
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (p.coverUrl != null) Modifier.shimmer() else Modifier),
            contentAlignment = Alignment.Center
        ) {
            if (p.coverUrl != null) {
                BlurredAsyncImage(thumbnailFor(p.coverUrl), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.PlaylistPlay, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
            }
            if (p.isFinished) {
                // Dim finished playlists and mark them with a check.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CheckCircle, "Finished", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
            // Count strip on the right, like YouTube's playlist covers.
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text("${if (p.isFinished) p.finishedCount else p.videoCount}", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            // ⋮ — the same actions as a long-press (open, rename, remove-finished option, delete).
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(
                    onClick = { menu = true },
                    modifier = Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = 0.45f))
                ) { Icon(Icons.Rounded.MoreVert, "Playlist options", tint = Color.White, modifier = Modifier.size(18.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onClick() })
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menu = false; onRename() }
                    )
                    if (p.isFinished) {
                        DropdownMenuItem(
                            text = { Text("Restart playlist") },
                            leadingIcon = { Icon(Icons.Rounded.Restore, null) },
                            onClick = { menu = false; onRestart() }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Remove videos as I finish them") },
                            leadingIcon = { Checkbox(checked = p.autoRemoveFinished, onCheckedChange = null) },
                            onClick = { menu = false; onToggleAutoRemove() }
                        )
                        if (p.finishedCount > 0) {
                            DropdownMenuItem(
                                text = { Text("Restore ${p.finishedCount} finished") },
                                leadingIcon = { Icon(Icons.Rounded.Restore, null) },
                                onClick = { menu = false; onRestart() }
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Delete playlist") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(p.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                p.isFinished -> {
                    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(p.finishedAt ?: 0L))
                    "All ${p.finishedCount} finished · $date"
                }
                p.autoRemoveFinished && p.finishedCount > 0 -> "${p.videoCount} left · ${p.finishedCount} finished"
                p.videoCount == 1 -> "1 video"
                else -> "${p.videoCount} videos"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "Add from YouTube link…": paste a playlist link (prefilled from the clipboard when it holds one) and open it. */
@Composable
private fun YoutubePlaylistLinkDialog(onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var text by remember {
        val clip = clipboard.getText()?.text.orEmpty()
        mutableStateOf(if (com.youtubly.app.core.LinkParser.parseText(clip) is com.youtubly.app.core.DeepLink.Playlist) clip else "")
    }
    val link = com.youtubly.app.core.LinkParser.parseText(text)
    val playlist = link as? com.youtubly.app.core.DeepLink.Playlist
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add from YouTube link") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Playlist link") },
                    placeholder = { Text("youtube.com/playlist?list=…") },
                    isError = text.isNotBlank() && playlist == null,
                    supportingText = {
                        when {
                            text.isBlank() -> Text("Paste the link of a public YouTube playlist.")
                            playlist != null -> Text("Playlist found.")
                            link != null -> Text("That's a video or channel link, not a playlist.")
                            else -> Text("That doesn't look like a playlist link.")
                        }
                    }
                )
            }
        },
        confirmButton = { TextButton(onClick = { playlist?.let { onOpen(it.url) } }, enabled = playlist != null) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Shared by create and rename. */
@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(100) },
                singleLine = true,
                label = { Text("Name") }
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
