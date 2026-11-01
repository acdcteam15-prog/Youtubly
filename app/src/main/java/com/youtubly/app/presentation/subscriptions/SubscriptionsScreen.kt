package com.youtubly.app.presentation.subscriptions

import com.youtubly.app.presentation.blur.BlurredAsyncImage

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import com.youtubly.app.presentation.components.staggeredEntrance
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.youtubly.app.domain.model.ChannelWatchStat
import com.youtubly.app.domain.model.SubscribedChannel
import com.youtubly.app.presentation.components.EmptyState
import com.youtubly.app.presentation.components.ErrorView
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.components.shimmer

/**
 * Manage subscriptions: search, sort by how much you actually watch each channel, pin
 * favourites to the top, and unsubscribe. Watch stats are local (from this app's history).
 */
@Composable
fun SubscriptionsScreen(
    onBack: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onSignIn: () -> Unit,
    viewModel: SubscriptionsViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<SubscribedChannel?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Subscriptions", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                if (ui.total > 0) {
                    Text("${ui.total} channels", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ui.signedIn) IconButton(onClick = viewModel::load) { Icon(Icons.Rounded.Refresh, "Refresh") }
        }

        when {
            !ui.signedIn && !ui.loading -> EmptyState(
                Icons.Rounded.Subscriptions,
                "Sign in to see your subscriptions",
                "Your channels come from your YouTube account.",
                actionLabel = "Sign in",
                onAction = onSignIn
            )
            ui.loading -> SkeletonFeed(count = 4)
            ui.error != null && ui.total == 0 -> ErrorView(ui.error!!, onRetry = viewModel::load)
            else -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    placeholder = { Text("Find a channel") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(ChannelSort.entries) { s ->
                        FilterChip(selected = sort == s, onClick = { viewModel.setSort(s) }, label = { Text(s.label) }, shape = MaterialTheme.shapes.extraLarge)
                    }
                }
                if (ui.pinned.isEmpty() && ui.others.isEmpty()) {
                    EmptyState(
                        Icons.Rounded.Search,
                        "No channels here",
                        if (sort == ChannelSort.NOT_WATCHED) "You've watched something from every channel shown." else "Nothing matches \"$query\"."
                    )
                } else LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (ui.pinned.isNotEmpty()) {
                        item(key = "h_pinned") { SectionLabel("Pinned") }
                        items(ui.pinned, key = { "p_" + it.channel.channelId }) { row ->
                            ChannelRowItem(modifier = Modifier.animateItem(), row = row, onOpen = { onOpenChannel(row.channel.url) }, onPin = { viewModel.togglePin(row) }, onUnsubscribe = { confirm = row.channel })
                        }
                        item(key = "h_all") { SectionLabel("All channels") }
                    }
                    itemsIndexed(ui.others, key = { _, it -> it.channel.channelId }) { i, row ->
                        ChannelRowItem(modifier = Modifier.animateItem().staggeredEntrance(i), row = row, onOpen = { onOpenChannel(row.channel.url) }, onPin = { viewModel.togglePin(row) }, onUnsubscribe = { confirm = row.channel })
                    }
                }
            }
        }
    }

    confirm?.let { ch ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Unsubscribe from ${ch.name}?") },
            text = { Text("This changes your YouTube account, not just this app.") },
            confirmButton = { TextButton(onClick = { viewModel.unsubscribe(ch); confirm = null }) { Text("Unsubscribe") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun ChannelRowItem(modifier: Modifier = Modifier, row: ChannelRow, onOpen: () -> Unit, onPin: () -> Unit, onUnsubscribe: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .bounceClick(scaleDown = 0.98f, onLongClick = { menu = true }, onClick = onOpen)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).shimmer()) {
            BlurredAsyncImage(row.channel.avatarUrl, row.channel.name, channel = com.youtubly.app.blur.channelKeyOf(row.channel.channelId), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.channel.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                statLine(row.stat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onPin) {
            Icon(
                if (row.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                if (row.pinned) "Unpin" else "Pin",
                tint = if (row.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open channel") }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) }, onClick = { menu = false; onOpen() })
                val ctx = androidx.compose.ui.platform.LocalContext.current
                DropdownMenuItem(
                    text = { Text("Share channel") },
                    leadingIcon = { Icon(Icons.Rounded.Share, null) },
                    onClick = { menu = false; com.youtubly.app.presentation.components.shareText(ctx, "${row.channel.name}: ${row.channel.url}", row.channel.name, "Share channel") }
                )
                DropdownMenuItem(
                    text = { Text("Unsubscribe") },
                    leadingIcon = { Icon(Icons.Rounded.PersonOff, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onUnsubscribe() }
                )
            }
        }
    }
}

/** "12 videos · 3 h 20 min · 2 days ago", or a hint when nothing has been watched here. */
private fun statLine(stat: ChannelWatchStat?, now: Long = System.currentTimeMillis()): String {
    if (stat == null || stat.videos == 0) return "Not watched in Youtubly yet"
    val mins = stat.watchedMs / 60_000
    val time = when {
        mins < 1 -> "under a minute"
        mins < 60 -> "$mins min"
        else -> "${mins / 60} h ${mins % 60} min"
    }
    return listOf("${stat.videos} video${if (stat.videos == 1) "" else "s"}", time, ago(now - stat.lastWatchedAt)).joinToString("  ·  ")
}

private fun ago(ms: Long): String {
    val days = ms / 86_400_000L
    return when {
        days < 1 -> "today"
        days < 2 -> "yesterday"
        days < 30 -> "$days days ago"
        days < 365 -> "${days / 30} mo ago"
        else -> "${days / 365} y ago"
    }
}
