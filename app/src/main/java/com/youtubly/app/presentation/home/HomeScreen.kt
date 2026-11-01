package com.youtubly.app.presentation.home

import com.youtubly.app.presentation.components.adaptiveItems
import com.youtubly.app.presentation.components.rememberFeedColumns
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import com.youtubly.app.domain.model.Video
import com.youtubly.app.presentation.components.AuroraBackground
import com.youtubly.app.presentation.components.EmptyState
import com.youtubly.app.presentation.components.ErrorView
import com.youtubly.app.presentation.components.GradientTitle
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.VideoCard
import com.youtubly.app.presentation.components.VideoCardSkeleton
import com.youtubly.app.presentation.components.WATCHED_FRACTION
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(
    onVideoClick: (Video) -> Unit,
    /** Opens a channel group's merged feed (the group chips next to Home / Subscriptions). */
    onOpenGroup: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val groupsVm: com.youtubly.app.presentation.library.ChannelGroupsViewModel = hiltViewModel()
    val groups by groupsVm.groups.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val resume by viewModel.continueWatching.collectAsStateWithLifecycle()
    val dismissedResume by viewModel.dismissedResumeId.collectAsStateWithLifecycle()
    val newSubs by viewModel.newSubsCount.collectAsStateWithLifecycle()
    // Loading already covers both the initial load and any manual pull-to-refresh — reusing
    // it here means the refresh spinner and the existing skeleton state can never disagree.
    val refreshing = state is HomeUiState.Loading
    // Home tab tapped: reload the feed (the list jumps to the top as the new items arrive).
    var handledTaps by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(HomeRefresh.taps.value) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        HomeRefresh.taps.collect { n ->
            if (n != handledTaps) { handledTaps = n; viewModel.load() }
        }
    }

    AuroraBackground {
        com.youtubly.app.presentation.components.Refreshable(refreshing, viewModel::load, Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                GradientTitle("Youtubly")
                Text(
                    if (signedIn) "Your YouTube, minus ads, Shorts and sponsors." else "Ad-free. Sponsor-free. Sign in on You for your feed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(14.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(feeds, key = { it.label }) { f ->
                    FilterChip(
                        selected = feed == f,
                        onClick = { viewModel.select(f) },
                        label = {
                            if (f == Feed.Subscriptions && newSubs > 0 && feed != f) {
                                androidx.compose.material3.BadgedBox(badge = {
                                    androidx.compose.material3.Badge { Text(if (newSubs > 99) "99+" else newSubs.toString()) }
                                }) { Text(f.label, modifier = Modifier.padding(end = 10.dp)) }
                            } else Text(f.label)
                        },
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
                // Your channel groups: tap one for the newest videos of just those channels.
                items(groups.keys.toList(), key = { "group_$it" }) { g ->
                    FilterChip(
                        selected = false,
                        onClick = { onOpenGroup(g) },
                        label = { Text(g) },
                        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Folder, null) },
                        shape = MaterialTheme.shapes.extraLarge
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
                contentKey = { it::class },
                label = "homeState"
            ) { s ->
                when (s) {
                    is HomeUiState.Loading -> SkeletonFeed()
                    is HomeUiState.Error -> ErrorView(s.message, onRetry = viewModel::load)
                    is HomeUiState.Success -> {
                        // Finished videos (>= WATCHED_FRACTION) are hidden here, at render time only:
                        // s.videos itself stays whole so pagination ("near end" / continuation) keeps
                        // counting real fetched pages, not what's left after filtering.
                        val watchProgress = com.youtubly.app.presentation.components.LocalWatchProgress.current.value
                        val visibleVideos = remember(s.videos, watchProgress) {
                            s.videos.filter { (watchProgress[it.id] ?: 0f) < WATCHED_FRACTION }
                        }
                        if (visibleVideos.isEmpty()) {
                            EmptyState(Icons.Rounded.Subscriptions, "Nothing here yet", "Try another category or check back later.")
                        } else {
                            val listState = rememberLazyListState()
                            val nearEnd by remember {
                                derivedStateOf {
                                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                    last >= listState.layoutInfo.totalItemsCount - 5
                                }
                            }
                            LaunchedEffect(nearEnd, s.videos.size) { if (nearEnd) viewModel.loadMore() }
                            val cols = rememberFeedColumns()
                            LazyColumn(state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
                                resume?.takeIf { it.id != dismissedResume }?.let { r ->
                                    item(key = "resume_${r.id}") {
                                        ContinueWatchingBanner(r, onClick = { onVideoClick(r) }, onDismiss = { viewModel.dismissResume(r.id) })
                                    }
                                }
                                adaptiveItems(visibleVideos, cols, key = { i, v -> "${v.id}_$i" }) { i, video ->
                                    VideoCard(
                                        video,
                                        onClick = { onVideoClick(video) },
                                        index = i % 8,
                                        compact = cols > 1
                                    )
                                }
                                if (s.loadingMore) {
                                    item { VideoCardSkeleton() }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}


@androidx.compose.runtime.Composable
private fun ContinueWatchingBanner(video: com.youtubly.app.domain.model.Video, onClick: () -> Unit, onDismiss: () -> Unit) {
    val progress = com.youtubly.app.presentation.components.LocalWatchProgress.current.value[video.id] ?: 0f
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.small)) {
            com.youtubly.app.presentation.blur.BlurredAsyncImage(
                model = com.youtubly.app.presentation.components.thumbnailFor(video.thumbnailUrl), contentDescription = null, channel = com.youtubly.app.blur.channelKeyOf(video.uploaderUrl),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("Continue watching", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                video.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        androidx.compose.material3.IconButton(onClick = onDismiss) {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
