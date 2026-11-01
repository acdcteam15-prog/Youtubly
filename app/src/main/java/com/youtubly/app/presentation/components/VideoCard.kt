package com.youtubly.app.presentation.components

import com.youtubly.app.presentation.blur.BlurredAsyncImage

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.NotInterested
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.youtubly.app.domain.model.Video
import com.youtubly.app.presentation.theme.BrandGradient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCard(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    /** On a channel's own page its videos stay visible even if the channel is blocked. */
    showBlockedChannel: Boolean = false,
    /** Grid cell (Phase 8 wide layout): tighter side padding so columns don't waste space. */
    compact: Boolean = false
) {
    val actions = LocalVideoActions.current
    val hidden by (actions?.hiddenVideos ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }).collectAsState()
    val blocked by (actions?.blockedChannels ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }).collectAsState()
    // Set when the user removed this card just now, so we show YouTube's inline "Undo" row
    // instead of silently collapsing it. Cards hidden in earlier sessions render nothing.
    var removedMessage by remember(video.id) { mutableStateOf<String?>(null) }
    val isHidden = video.id in hidden || (!showBlockedChannel && actions?.isChannelBlocked(video, blocked) == true)
    if (isHidden) {
        removedMessage?.let { msg -> RemovedRow(msg, onUndo = { actions?.undo(video); removedMessage = null }) }
        return
    }

    var sheetOpen by remember(video.id) { mutableStateOf(false) }
    var downloadInfo by remember(video.id) { mutableStateOf<com.youtubly.app.domain.model.StreamInfoResult?>(null) }
    var saveSheet by remember { mutableStateOf(false) }
    val branding = rememberBranding(video.id)
    val progress = LocalWatchProgress.current.value[video.id] ?: 0f
    val shownThumb = branding?.thumbnailUrl ?: thumbnailFor(video.thumbnailUrl)
    val blurKey = remember(video.uploaderUrl) { com.youtubly.app.blur.channelKeyOf(video.uploaderUrl) }
    val blurActive = com.youtubly.app.presentation.blur.rememberBlurActive(blurKey, videoId = video.id)
    // Swipe right = Not interested, swipe left = Don't recommend channel (same as the ⋮ menu).
    // On a channel's own page, blocking that channel would hide nothing, so only "not interested".
    SwipeVideoActions(
        onNotInterested = actions?.let { a -> { removedMessage = "Video removed"; a.notInterested(video) } },
        onDontRecommendChannel = actions?.takeIf { !showBlockedChannel && video.uploaderName.isNotBlank() }?.let { a ->
            { removedMessage = "We won't recommend videos from ${video.uploaderName}"; a.dontRecommendChannel(video) }
        },
        modifier = modifier.staggeredEntrance(index)
    ) {
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.background)
            .bounceClick(onLongClick = { sheetOpen = true }, onClick = {
                SharedThumbs.put(video.url, shownThumb)
                onClick()
            })
            .padding(horizontal = if (compact) 8.dp else 16.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.large)
                .shimmer()
        ) {
            BlurredAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(shownThumb)
                    .crossfade(350)
                    .build(),
                contentDescription = video.title,
                channel = blurKey,
                videoId = video.id,
                contentScale = ContentScale.Crop,
                // DeArrow thumbnails are rendered on demand; fall back to YouTube's if not ready yet.
                // (Skipped while blurring: a raw fallback painter would bypass the blur.)
                error = if (blurActive) null else rememberAsyncImagePainter(thumbnailFor(video.thumbnailUrl)),
                // Flies into the player's video area on tap (see PlayerScreen's PosterOverlay).
                modifier = Modifier.fillMaxSize().sharedThumbnail(video.url)
            )
            // bottom scrim so badges stay legible on bright thumbnails
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
            )
            WatchProgressOverlay(progress, barHeight = 4.dp)
            when {
                video.isLive -> Badge("● LIVE", MaterialTheme.colorScheme.primary, Modifier.align(Alignment.BottomEnd).padding(10.dp))
                video.duration > 0 -> Badge(formatDuration(video.duration), Color.Black.copy(alpha = 0.72f), Modifier.align(Alignment.BottomEnd).padding(10.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            ChannelAvatar(video)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    branding?.title ?: video.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(
                        video.uploaderName.takeIf { it.isNotBlank() },
                        video.viewCount.takeIf { it >= 0 }?.let { "${formatCount(it)} views" },
                        video.uploadDate?.takeIf { it.isNotBlank() }?.let(::prettyDate)
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { sheetOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.MoreVert, "More options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    }

    if (sheetOpen) {
        VideoActionsSheet(
            video = video,
            title = branding?.title ?: video.title,
            onDismiss = { sheetOpen = false },
            onRemoved = { removedMessage = it },
            onDownloadReady = { downloadInfo = it },
            onSaveToPlaylist = { saveSheet = true }
        )
    }
    if (saveSheet) SaveToPlaylistSheet(video, onDismiss = { saveSheet = false })
    downloadInfo?.let { info ->
        val ctx = LocalContext.current
        DownloadQualitySheet(
            info = info,
            onPick = { option ->
                downloadInfo = null
                actions?.launchUi {
                    val r = actions.queueDownload(info, option)
                    android.widget.Toast.makeText(
                        ctx,
                        if (r.isSuccess) "Downloading ${option.label}" else "Download failed: ${r.exceptionOrNull()?.message ?: "unknown"}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { downloadInfo = null }
        )
    }
}

/** YouTube's long-press / ⋮ bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoActionsSheet(
    video: Video,
    title: String,
    onDismiss: () -> Unit,
    onRemoved: (String) -> Unit,
    onDownloadReady: (com.youtubly.app.domain.model.StreamInfoResult) -> Unit,
    onSaveToPlaylist: () -> Unit
) {
    val context = LocalContext.current
    val actions = LocalVideoActions.current
    val openChannel = LocalOpenChannel.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss(); then() }
    }
    fun toast(msg: String) = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)
        )
        SheetItem(Icons.Rounded.WatchLater, "Save to Watch later") {
            close {
                actions?.launchUi {
                    val r = actions.saveToWatchLater(video)
                    toast(if (r.isSuccess == true) "Saved to Watch later" else "Sign in to save to Watch later")
                }
            }
        }
        if (LocalPlaylists.current != null) {
            SheetItem(Icons.Rounded.PlaylistAdd, "Save to playlist…") { close(onSaveToPlaylist) }
        }
        SheetItem(Icons.Rounded.Download, "Download…") {
            close {
                toast("Loading qualities…")
                actions?.launchUi {
                    actions.resolveForDownload(video)
                        .onSuccess(onDownloadReady)
                        .onFailure { toast("Couldn't load streams: ${it.message ?: "unknown"}") }
                }
            }
        }
        SheetItem(Icons.Rounded.Share, "Share") {
            close {
                shareText(context, com.youtubly.app.core.ShareLinks.short(video.url), title, "Share video")
            }
        }
        SheetItem(Icons.Rounded.Link, "Copy link") {
            close { clipboard.setText(AnnotatedString(com.youtubly.app.core.ShareLinks.short(video.url))); toast("Link copied") }
        }
        if (openChannel != null && video.uploaderUrl != null) {
            SheetItem(Icons.Rounded.AccountCircle, "Go to channel") { close { openChannel(video.uploaderUrl) } }
        }
        val blurRef = remember(video.uploaderUrl) {
            com.youtubly.app.blur.channelKeyOf(video.uploaderUrl)?.let { com.youtubly.app.blur.ChannelRef(it, video.uploaderName, video.uploaderAvatarUrl) }
        }
        com.youtubly.app.presentation.blur.ChannelBlurSheetItem(blurRef) { close { } }
        com.youtubly.app.presentation.blur.VideoBlurSheetItem(video.id) { close { } }
        com.youtubly.app.presentation.music.ChannelMusicSheetItem(blurRef) { close { } }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SheetItem(Icons.Rounded.NotInterested, "Not interested") {
            close { actions?.notInterested(video); onRemoved("Video removed") }
        }
        if (video.uploaderName.isNotBlank()) {
            SheetItem(Icons.Rounded.PersonOff, "Don't recommend channel") {
                close { actions?.dontRecommendChannel(video); onRemoved("We won't recommend videos from ${video.uploaderName}") }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RemovedRow(message: String, onUndo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        TextButton(onClick = onUndo) { Text("Undo") }
    }
}

@Composable
private fun Badge(text: String, bg: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

fun formatCount(count: Long): String = when {
    count >= 1_000_000_000 -> "%.1fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}.replace(".0", "")

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** ISO timestamps (Data API / player) → "3 days ago"; NewPipe's "3 days ago" passes through. */
fun prettyDate(raw: String): String {
    if (!(raw.length >= 10 && raw[4] == '-' && raw[7] == '-')) return raw
    val then = runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(raw.substring(0, 10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()
        ?: return raw.substring(0, 10)
    val secs = java.time.Duration.between(then, java.time.Instant.now()).seconds.coerceAtLeast(0)
    fun ago(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        secs < 60 -> "just now"
        secs < 3600 -> ago(secs / 60, "minute")
        secs < 86_400 -> ago(secs / 3600, "hour")
        secs < 604_800 -> ago(secs / 86_400, "day")
        secs < 2_592_000 -> ago(secs / 604_800, "week")
        secs < 31_536_000 -> ago(secs / 2_592_000, "month")
        else -> ago(secs / 31_536_000, "year")
    }
}


/** Real channel avatar when known, else the gradient letter badge. Tap → channel. */
@Composable
fun ChannelAvatar(video: Video, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val openChannel = LocalOpenChannel.current
    val tap = if (openChannel != null && video.uploaderUrl != null) Modifier.clickable { openChannel(video.uploaderUrl) } else Modifier
    Box(
        Modifier.size(size).clip(CircleShape).background(BrandGradient).then(tap),
        contentAlignment = Alignment.Center
    ) {
        Text(
            video.uploaderName.take(1).uppercase(),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black
        )
        if (video.uploaderAvatarUrl != null) {
            BlurredAsyncImage(
                model = video.uploaderAvatarUrl,
                contentDescription = video.uploaderName,
                channel = com.youtubly.app.blur.channelKeyOf(video.uploaderUrl),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
