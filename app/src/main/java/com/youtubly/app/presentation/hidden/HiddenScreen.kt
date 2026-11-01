package com.youtubly.app.presentation.hidden

import com.youtubly.app.presentation.blur.BlurredAsyncImage

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.NotInterested
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.youtubly.app.playback.HiddenItem
import com.youtubly.app.presentation.components.EmptyState
import com.youtubly.app.presentation.components.shimmer

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HiddenScreen(onBack: () -> Unit, viewModel: HiddenViewModel = hiltViewModel()) {
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val list = if (tab == 0) videos else channels

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
            Text(
                "Hidden videos & channels",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { if (tab == 0) viewModel.restoreAllVideos() else viewModel.restoreAllChannels() },
                enabled = list.isNotEmpty()
            ) { Text("Restore all") }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Videos · ${videos.size}") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Channels · ${channels.size}") })
        }
        if (list.isEmpty()) {
            if (tab == 0) EmptyState(Icons.Rounded.NotInterested, "No hidden videos", "Videos you mark \"Not interested\" show up here.")
            else EmptyState(Icons.Rounded.PersonOff, "No blocked channels", "Channels you mark \"Don't recommend\" show up here.")
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
                items(list, key = { it.key }) { item ->
                    Box(Modifier.animateItem().padding(bottom = 8.dp)) {
                        HiddenRow(item, isChannel = tab == 1) {
                            if (tab == 0) viewModel.restoreVideo(item) else viewModel.restoreChannel(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HiddenRow(item: HiddenItem, isChannel: Boolean, onUndo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isChannel) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (item.thumbnailUrl != null) {
                    BlurredAsyncImage(item.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium).shimmer()) {
                BlurredAsyncImage(item.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onUndo) { Text("Undo") }
    }
}
