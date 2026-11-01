package com.youtubly.app.presentation.search

import com.youtubly.app.presentation.blur.BlurredAsyncImage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.youtubly.app.domain.model.Video
import com.youtubly.app.presentation.components.WatchProgressOverlay
import com.youtubly.app.presentation.components.thumbnailFor
import com.youtubly.app.presentation.components.watchProgressOf

/** One row in "In your library", already bound to what tapping it should do. */
data class LibraryHitRow(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    /** Thumbnail for video rows; null draws the icon in a circle instead. */
    val thumbnailUrl: String?,
    val videoId: String?,
    val onClick: () -> Unit
)

/** Flattens [LibraryResults] into rows, most specific first: channels, playlists, saved, downloads, history, notes. */
fun LibraryResults.toRows(
    onVideo: (Video) -> Unit,
    onPlaylist: (Long) -> Unit,
    onDownload: (String) -> Unit,
    onChannel: (String) -> Unit
): List<LibraryHitRow> = buildList {
    channels.forEach { c ->
        add(LibraryHitRow("c_${c.channelId}", Icons.Rounded.Person, c.name, "Subscribed channel", null, null) { onChannel(c.url) })
    }
    playlists.forEach { p ->
        add(LibraryHitRow("p_${p.id}", Icons.Rounded.PlaylistPlay, p.name, "Your playlist", null, null) { onPlaylist(p.id) })
    }
    saved.forEach { h ->
        add(LibraryHitRow("s_${h.video.id}", Icons.Rounded.BookmarkAdded, h.video.title, "In “${h.playlistName}” · ${h.video.uploaderName}", h.video.thumbnailUrl, h.video.id) { onVideo(h.video) })
    }
    downloads.forEach { d ->
        add(LibraryHitRow("d_${d.id}", Icons.Rounded.DownloadDone, d.title, "Downloaded" + (d.qualityLabel?.let { " · $it" } ?: ""), d.thumbnailUrl, null) { onDownload(d.id) })
    }
    history.forEach { v ->
        add(LibraryHitRow("h_${v.id}", Icons.Rounded.History, v.title, "Watched · ${v.uploaderName}", v.thumbnailUrl, v.id) { onVideo(v) })
    }
    notes.forEach { n ->
        val video = Video(n.videoId, n.videoUrl, n.videoTitle, n.uploaderName, null, null, 0, -1, null)
        add(LibraryHitRow("n_${n.id}", Icons.Rounded.EditNote, n.text, "Note at ${com.youtubly.app.domain.playback.Transcript.stamp(n.positionMs)} · ${n.videoTitle}", null, null) { onVideo(video) })
    }
}

@Composable
fun LibraryHitItem(row: LibraryHitRow, dense: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = row.onClick)
            .padding(horizontal = if (dense) 16.dp else 8.dp, vertical = if (dense) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.thumbnailUrl != null) {
            Box(Modifier.width(if (dense) 64.dp else 96.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small)) {
                BlurredAsyncImage(thumbnailFor(row.thumbnailUrl), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                row.videoId?.let { WatchProgressOverlay(watchProgressOf(it), showWatchedTag = false) }
            }
        } else {
            Box(
                Modifier.size(if (dense) 36.dp else 44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) { Icon(row.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.thumbnailUrl != null) {
                    Icon(row.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Collapsible "In your library" card shown above YouTube's results. */
@Composable
fun LibraryResultsCard(rows: List<LibraryHitRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    var expanded by remember(rows) { mutableStateOf(false) }
    val shown = if (expanded) rows else rows.take(3)
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 8.dp)
    ) {
        Text(
            "In your library · ${rows.size}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        shown.forEach { LibraryHitItem(it) }
        if (rows.size > 3) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(start = 4.dp)) {
                Text(if (expanded) "Show less" else "Show all ${rows.size}")
            }
        }
    }
}
