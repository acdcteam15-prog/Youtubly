package com.youtubly.app.presentation.channel

import com.youtubly.app.presentation.blur.BlurredAsyncImage

import com.youtubly.app.presentation.components.adaptiveItems
import com.youtubly.app.presentation.components.rememberFeedColumns
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.youtubly.app.domain.model.Channel
import com.youtubly.app.domain.model.ChannelTabKind
import com.youtubly.app.domain.channel.VideoSort
import com.youtubly.app.domain.channel.PlaylistProgress
import com.youtubly.app.domain.model.Video
import com.youtubly.app.presentation.components.ErrorView
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.VideoCard
import com.youtubly.app.presentation.components.formatCount
import com.youtubly.app.presentation.components.shimmer
import com.youtubly.app.presentation.theme.BrandGradient

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun ChannelScreen(
    channelUrl: String,
    onBack: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onPlaylistClick: (com.youtubly.app.domain.model.ChannelPlaylist) -> Unit = {},
    viewModel: ChannelViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val feeds by viewModel.videoFeeds.collectAsStateWithLifecycle()
    val playlistsFeed by viewModel.playlists.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val showShorts by viewModel.showShorts.collectAsStateWithLifecycle()
    val continueVideo by viewModel.continueVideo.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(channelUrl) { viewModel.load(channelUrl) }
    androidx.activity.compose.BackHandler(enabled = search.open) { viewModel.closeSearch() }
    val refreshing = state is ChannelUiState.Loading

    com.youtubly.app.presentation.components.Refreshable(
        refreshing = refreshing,
        onRefresh = { viewModel.load(channelUrl, force = true) },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
            contentKey = { it::class },
            label = "channelState"
        ) { s ->
            when (s) {
                is ChannelUiState.Loading -> Column(Modifier.statusBarsPadding().padding(top = 56.dp)) { SkeletonFeed() }
                is ChannelUiState.Error -> ErrorView(s.message, onRetry = { viewModel.load(channelUrl, force = true) })
                is ChannelUiState.Success -> ChannelContent(
                    channel = s.channel,
                    tab = tab,
                    feeds = feeds,
                    playlists = playlistsFeed,
                    sort = sort,
                    showShorts = showShorts,
                    continueVideo = continueVideo,
                    playlistIds = viewModel::playlistVideoIds,
                    onTab = viewModel::selectTab,
                    onSort = viewModel::setSort,
                    onLoadMore = viewModel::loadMore,
                    onLoadAll = viewModel::loadAll,
                    onRetry = viewModel::retryTab,
                    onVideoClick = onVideoClick,
                    onPlaylistClick = onPlaylistClick,
                    onOpenSettings = { showSettings = true }
                )
            }
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
        if (state is ChannelUiState.Success) {
            IconButton(
                onClick = viewModel::openSearch,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
            ) { Icon(Icons.Rounded.Search, "Search this channel", tint = Color.White) }
            // The three-dot menu next to search: Channel settings.
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 64.dp)) {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
                ) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Channel settings") },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                        onClick = { menuOpen = false; showSettings = true }
                    )
                }
            }
        }
        val channelName = (state as? ChannelUiState.Success)?.channel?.name.orEmpty()
        androidx.compose.animation.AnimatedVisibility(
            visible = search.open,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150))
        ) {
            ChannelSearchOverlay(
                state = search,
                channelName = channelName,
                onQuery = viewModel::setQuery,
                onSubmit = viewModel::submitSearch,
                onClose = viewModel::closeSearch,
                onLoadMore = viewModel::loadMoreResults,
                onVideoClick = onVideoClick
            )
        }
    }
    val settingsChannel = (state as? ChannelUiState.Success)?.channel
    val settingsRef = settingsChannel?.let { ch -> com.youtubly.app.blur.channelKeyOf(ch.url)?.let { com.youtubly.app.blur.ChannelRef(it, ch.name, ch.avatarUrl) } }
    if (showSettings && settingsRef != null) ChannelSettingsSheet(settingsRef, onDismiss = { showSettings = false })
}

@Composable
private fun ChannelSearchOverlay(
    state: ChannelSearchState,
    channelName: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    onLoadMore: () -> Unit,
    onVideoClick: (Video) -> Unit
) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") }
            androidx.compose.material3.OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text("Search $channelName", maxLines = 1) },
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide(); onSubmit() }),
                trailingIcon = {
                    IconButton(onClick = { keyboard?.hide(); onSubmit() }) { Icon(Icons.Rounded.Search, "Search") }
                },
                modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus)
            )
        }
        when {
            state.error != null -> ErrorView(state.error, onRetry = onSubmit)
            state.loading && state.videos.isEmpty() -> SkeletonFeed(count = 3)
            state.submitted.isNotEmpty() && state.videos.isEmpty() ->
                Text(
                    "No videos from $channelName match \"${state.submitted}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                )
            else -> {
                val listState = rememberLazyListState()
                val nearEnd by remember {
                    derivedStateOf {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= listState.layoutInfo.totalItemsCount - 3
                    }
                }
                LaunchedEffect(nearEnd, state.continuation) { if (nearEnd && state.continuation != null) onLoadMore() }
                val cols = rememberFeedColumns()
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
                    adaptiveItems(state.videos, cols, key = { i, v -> "${v.id}_$i" }) { i, v ->
                        VideoCard(v, onClick = { onVideoClick(v) }, index = i, showBlockedChannel = true, compact = cols > 1)
                    }
                    if (state.loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelContent(
    channel: Channel,
    tab: ChannelTabKind,
    feeds: Map<ChannelTabKind, ChannelFeed<Video>>,
    playlists: ChannelFeed<com.youtubly.app.domain.model.ChannelPlaylist>,
    sort: VideoSort,
    showShorts: Boolean,
    continueVideo: Video?,
    onOpenSettings: () -> Unit,
    playlistIds: (String) -> List<String>,
    onTab: (ChannelTabKind) -> Unit,
    onSort: (VideoSort) -> Unit,
    onLoadMore: () -> Unit,
    onLoadAll: () -> Unit,
    onRetry: () -> Unit,
    onVideoClick: (Video) -> Unit,
    onPlaylistClick: (com.youtubly.app.domain.model.ChannelPlaylist) -> Unit
) {
    val listState = rememberLazyListState()
    // Tabs this channel has; Shorts only when switched on in Settings.
    val tabs = remember(channel.tabs, showShorts) {
        buildList {
            add(ChannelTabKind.VIDEOS)
            if (ChannelTabKind.LIVE in channel.tabs) add(ChannelTabKind.LIVE)
            if (ChannelTabKind.SHORTS in channel.tabs && showShorts) add(ChannelTabKind.SHORTS)
            if (ChannelTabKind.PLAYLISTS in channel.tabs) add(ChannelTabKind.PLAYLISTS)
        }
    }
    val activeTab = if (tab in tabs) tab else ChannelTabKind.VIDEOS
    val isPlaylists = activeTab == ChannelTabKind.PLAYLISTS
    val feed = feeds[activeTab] ?: ChannelFeed()
    val sortable = activeTab == ChannelTabKind.VIDEOS || activeTab == ChannelTabKind.LIVE
    val activeSort = if (sortable) sort else VideoSort.LATEST
    val shown = remember(feed.items, activeSort) { activeSort.apply(feed.items) }
    // Scroll paging only while the order is stable: re-sorting under your thumb would shuffle the list.
    val pagingNext = when {
        isPlaylists -> playlists.next
        activeSort == VideoSort.LATEST -> feed.next
        else -> null
    }
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, pagingNext, activeTab) { if (nearEnd && pagingNext != null) onLoadMore() }
    // Parallax: banner drifts at half speed and fades as you scroll.
    val scroll by remember {
        derivedStateOf { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else 1000f }
    }

    val cols = rememberFeedColumns()
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 48.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
        item {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .graphicsLayer {
                            translationY = scroll * 0.5f
                            alpha = (1f - scroll / 600f).coerceIn(0f, 1f)
                        }
                        .background(BrandGradient)
                ) {
                    if (channel.bannerUrl != null) {
                        BlurredAsyncImage(
                            model = channel.bannerUrl,
                            contentDescription = null,
                            channel = com.youtubly.app.blur.channelKeyOf(channel.url),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))
                        )
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-8).dp)
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(BrandGradient)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .shimmer()
                ) {
                    BlurredAsyncImage(
                        model = channel.avatarUrl,
                        contentDescription = channel.name,
                        channel = com.youtubly.app.blur.channelKeyOf(channel.url),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                if (channel.subscriberCount >= 0) {
                    Text(
                        "${formatCount(channel.subscriberCount)} subscribers",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val blurRef = androidx.compose.runtime.remember(channel.url) { com.youtubly.app.blur.channelKeyOf(channel.url)?.let { com.youtubly.app.blur.ChannelRef(it, channel.name, channel.avatarUrl) } }
                androidx.compose.foundation.layout.Row(Modifier.padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    com.youtubly.app.presentation.blur.ChannelBlurChip(blurRef)
                    androidx.compose.material3.AssistChip(
                        onClick = onOpenSettings,
                        label = { Text("Channel settings") },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null) }
                    )
                }
                continueVideo?.let { v ->
                    androidx.compose.material3.Button(
                        onClick = { onVideoClick(v) },
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Continue: ${v.title}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                channel.description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        item(key = "groups") {
            com.youtubly.app.presentation.library.ChannelGroupChips(channel.url, Modifier.padding(bottom = 8.dp))
        }
        if (tabs.size > 1) {
            item {
                androidx.compose.material3.TabRow(
                    selectedTabIndex = tabs.indexOf(activeTab),
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    tabs.forEach { t ->
                        androidx.compose.material3.Tab(
                            selected = activeTab == t,
                            onClick = { onTab(t) },
                            text = {
                                Text(
                                    when (t) {
                                        ChannelTabKind.VIDEOS -> "Videos"
                                        ChannelTabKind.LIVE -> "Live"
                                        ChannelTabKind.SHORTS -> "Shorts"
                                        ChannelTabKind.PLAYLISTS -> "Playlists"
                                    },
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }
        }
        if (sortable && feed.items.size > 1) {
            item {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                    ) {
                        VideoSort.entries.forEach { o ->
                            androidx.compose.material3.FilterChip(
                                selected = activeSort == o, onClick = { onSort(o) },
                                label = { Text(o.label) }, shape = MaterialTheme.shapes.extraLarge
                            )
                        }
                    }
                    // Popular / Oldest are only exact over the whole list; say so and offer to load it all.
                    if (activeSort != VideoSort.LATEST && feed.next != null) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Sorted among the ${feed.items.size} videos loaded so far.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (feed.loading) {
                                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                androidx.compose.material3.TextButton(onClick = onLoadAll) { Text("Load all") }
                            }
                        }
                    }
                }
            }
        }
        if (isPlaylists) {
            adaptiveItems(playlists.items, cols, key = { i, p -> "${p.url}_$i" }) { _, p ->
                val ids = remember(p.url, playlists.items.size) { playlistIds(p.url) }
                PlaylistCard(
                    p,
                    channelKey = com.youtubly.app.blur.channelKeyOf(channel.url),
                    watched = PlaylistProgress.watchedCount(ids, com.youtubly.app.presentation.components.LocalWatchProgress.current.value),
                    knownVideos = ids.size,
                    onClick = { onPlaylistClick(p) },
                    compact = cols > 1
                )
            }
        } else {
            adaptiveItems(shown, cols, key = { i, v -> "${v.id}_$i" }) { i, v ->
                VideoCard(v, onClick = { onVideoClick(v) }, index = i, compact = cols > 1, showBlockedChannel = true)
            }
        }
        val loading = if (isPlaylists) playlists.loading else feed.loading && !(activeSort != VideoSort.LATEST && feed.next != null)
        val error = if (isPlaylists) playlists.error else feed.error
        val empty = if (isPlaylists) playlists.started && !playlists.loading && playlists.error == null && playlists.items.isEmpty()
        else feed.started && !feed.loading && feed.error == null && feed.items.isEmpty()
        item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    error != null -> androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        androidx.compose.material3.TextButton(onClick = onRetry) { Text("Retry") }
                    }
                    empty -> Text(
                        when (activeTab) {
                            ChannelTabKind.PLAYLISTS -> "No playlists on this channel"
                            ChannelTabKind.LIVE -> "No live streams on this channel"
                            ChannelTabKind.SHORTS -> "No Shorts on this channel"
                            ChannelTabKind.VIDEOS -> "No videos on this channel"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** A playlist in the channel's Playlists tab: thumbnail with the video count, title, channel. */
@Composable
private fun PlaylistCard(
    p: com.youtubly.app.domain.model.ChannelPlaylist,
    channelKey: String?,
    /** Videos of this playlist already watched (0 until the playlist was opened once) and how many were known. */
    watched: Int,
    knownVideos: Int,
    onClick: () -> Unit,
    compact: Boolean
) {
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = if (compact) 8.dp else 16.dp, vertical = 8.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            BlurredAsyncImage(
                model = p.thumbnailUrl,
                contentDescription = p.title,
                channel = channelKey,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Count badge, like YouTube's playlist side panel.
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(MaterialTheme.shapes.small)
                    .background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (p.videoCount >= 0) "${formatCount(p.videoCount)} videos" else "Playlist", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            p.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        p.uploaderName?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (knownVideos > 0) {
            val total = if (p.videoCount > 0) p.videoCount.toInt() else knownVideos
            Text(
                if (watched >= total) "All $total watched" else "$watched of $total watched",
                style = MaterialTheme.typography.labelMedium,
                color = if (watched > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
