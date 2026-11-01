package com.youtubly.app.presentation.search

import com.youtubly.app.presentation.components.adaptiveItems
import com.youtubly.app.presentation.components.rememberFeedColumns
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.domain.model.Video
import com.youtubly.app.presentation.components.EmptyState
import com.youtubly.app.presentation.components.ErrorView
import com.youtubly.app.presentation.components.SkeletonFeed
import com.youtubly.app.presentation.components.VideoCard
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.theme.BrandGradientH

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onVideoClick: (Video) -> Unit,
    onOpenPlaylist: (Long) -> Unit = {},
    onOpenDownload: (String) -> Unit = {},
    /** A YouTube link typed or pasted into the search box (video, channel or playlist). */
    onOpenLink: (com.youtubly.app.core.DeepLink) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel()
) {
    var query by rememberSaveable { mutableStateOf("") }
    var focused by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val searchRefreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val openChannel = com.youtubly.app.presentation.components.LocalOpenChannel.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val borderColor by animateColorAsState(
        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        tween(300), label = "border"
    )

    fun submit(q: String = query) {
        if (q.isBlank()) return
        // A pasted YouTube link opens directly instead of being searched for.
        com.youtubly.app.core.LinkParser.parseText(q)?.let { link ->
            keyboard?.hide(); focus.clearFocus(); onOpenLink(link); return
        }
        query = q
        viewModel.onQueryChange(q)
        viewModel.search(q)
        keyboard?.hide()
        focus.clearFocus()
    }

    var filters by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = SearchFilters.Saver) { mutableStateOf(SearchFilters()) }
    // Library rows: leaving the search box first, so the keyboard doesn't linger over what opens.
    fun leave(action: () -> Unit) { keyboard?.hide(); focus.clearFocus(); action() }
    val libraryRows = remember(library) {
        library.toRows(
            onVideo = { v -> leave { onVideoClick(v) } },
            onPlaylist = { id -> leave { onOpenPlaylist(id) } },
            onDownload = { id -> leave { onOpenDownload(id) } },
            onChannel = { url -> leave { openChannel?.invoke(url) } }
        )
    }
    // Only rows for what's actually in the box (the flow lags typing by ~150 ms).
    val liveRows = if (library.query == query.trim()) libraryRows else emptyList()
    val showSuggestions = focused && query.isNotBlank() && (suggestions.isNotEmpty() || liveRows.isNotEmpty())

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ---- Search bar ----
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.5.dp, borderColor, RoundedCornerShape(28.dp))
                .padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it; viewModel.onQueryChange(it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }, onGo = { submit() }),
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text("Search YouTube", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    }
                }
            )
            AnimatedVisibility(visible = query.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                IconButton(onClick = { query = ""; viewModel.clear() }) { Icon(Icons.Rounded.Close, "Clear") }
            }
            // Tappable search button (gradient circle)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(BrandGradientH)
                    .clickable { submit() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Search, "Search", tint = Color.White) }
        }

        // ---- Live suggestions ----
        AnimatedVisibility(showSuggestions, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            LazyColumn(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
                if (liveRows.isNotEmpty()) {
                    item(key = "lib_h") {
                        Text(
                            "In your library",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)
                        )
                    }
                    items(liveRows.take(4), key = { "lib_" + it.key }) { LibraryHitItem(it, dense = true) }
                    if (suggestions.isNotEmpty()) item(key = "lib_div") {
                        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                }
                items(suggestions, key = { it }) { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { submit(s) }
                            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(
                            s,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // Fill the box without searching, to refine the query
                        IconButton(onClick = { query = "$s "; viewModel.onQueryChange(query) }) {
                            Icon(Icons.Rounded.NorthWest, "Use suggestion", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        if (!showSuggestions) {
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(180)) },
                contentKey = { it::class },
                label = "searchState"
            ) { s ->
                when (s) {
                    is SearchUiState.Idle ->
                        if (recent.isEmpty()) {
                            EmptyState(Icons.Rounded.ManageSearch, "Find anything", "Search without ads, trackers, or sponsor reads.")
                        } else {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Recent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "Clear",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.bounceClick { viewModel.clearHistory() }
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                LazyColumn(contentPadding = PaddingValues(bottom = 120.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
                                    items(recent, key = { it }) { r ->
                                        RecentSearchRow(
                                            text = r,
                                            onClick = { submit(r) },
                                            onFill = { query = "$r "; viewModel.onQueryChange(query) },
                                            onDelete = { viewModel.removeFromHistory(r) }
                                        )
                                    }
                                }
                            }
                        }
                    is SearchUiState.Loading -> SkeletonFeed()
                    is SearchUiState.Error -> ErrorView(s.message, onRetry = { submit() })
                    is SearchUiState.Success ->
                        if (s.videos.isEmpty()) {
                            EmptyState(Icons.Rounded.SearchOff, "No results", "Nothing matched \"${s.query}\".")
                        } else {
                            val filtered = remember(s.videos, filters) { s.videos.filter { v -> filters.matches(v) } }
                            val resultRows = if (library.query == s.query) libraryRows else emptyList()
                            com.youtubly.app.presentation.components.Refreshable(
                                refreshing = searchRefreshing,
                                onRefresh = viewModel::refresh,
                                indicatorBelowStatusBar = false
                            ) {
                            val cols = rememberFeedColumns()
                            LazyColumn(contentPadding = PaddingValues(bottom = 120.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
                                item(key = "filters") { SearchFilterBar(filters, onChange = { filters = it }) }
                                if (resultRows.isNotEmpty()) item(key = "library") { LibraryResultsCard(resultRows) }
                                if (filtered.isEmpty()) item(key = "nofilter") {
                                    Text(
                                        "No results match these filters.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(24.dp)
                                    )
                                }
                                adaptiveItems(filtered, cols, key = { i, v -> "${v.id}_$i" }) { i, v ->
                                    VideoCard(
                                        v,
                                        onClick = { onVideoClick(v) },
                                        index = i,
                                        compact = cols > 1
                                    )
                                }
                            }
                            }
                        }
                }
            }
        }
    }
}


// ---------------- Recent searches (swipe to delete) ----------------

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RecentSearchRow(text: String, onClick: () -> Unit, onFill: () -> Unit, onDelete: () -> Unit) {
    val dismiss = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { v -> if (v != androidx.compose.material3.SwipeToDismissBoxValue.Settled) { onDelete(); true } else false }
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = dismiss,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    ) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable(onClick = onClick)
                .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(androidx.compose.material.icons.Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onFill) { Icon(androidx.compose.material.icons.Icons.Rounded.NorthWest, "Edit search", modifier = Modifier.size(18.dp)) }
        }
    }
}

// ---------------- Result filters (applied on-device) ----------------

enum class DurationFilter(val label: String) { ANY("Any length"), SHORT("Under 4 min"), MEDIUM("4–20 min"), LONG("Over 20 min") }
enum class DateFilter(val label: String, val maxSeconds: Long) {
    ANY("Any time", Long.MAX_VALUE), HOUR("Last hour", 3_600), TODAY("Today", 86_400),
    WEEK("This week", 604_800), MONTH("This month", 2_678_400), YEAR("This year", 31_622_400)
}
enum class TypeFilter(val label: String) { ALL("All"), VIDEOS("Videos"), LIVE("Live") }

data class SearchFilters(
    val duration: DurationFilter = DurationFilter.ANY,
    val date: DateFilter = DateFilter.ANY,
    val type: TypeFilter = TypeFilter.ALL
) {
    val active get() = duration != DurationFilter.ANY || date != DateFilter.ANY || type != TypeFilter.ALL

    fun matches(v: com.youtubly.app.domain.model.Video): Boolean {
        when (type) {
            TypeFilter.VIDEOS -> if (v.isLive) return false
            TypeFilter.LIVE -> if (!v.isLive) return false
            TypeFilter.ALL -> Unit
        }
        if (duration != DurationFilter.ANY) {
            if (v.isLive || v.duration <= 0) return false
            val ok = when (duration) {
                DurationFilter.SHORT -> v.duration < 240
                DurationFilter.MEDIUM -> v.duration in 240..1200
                DurationFilter.LONG -> v.duration > 1200
                DurationFilter.ANY -> true
            }
            if (!ok) return false
        }
        if (date != DateFilter.ANY) {
            val age = ageSeconds(v.uploadDate) ?: return false
            if (age > date.maxSeconds) return false
        }
        return true
    }

    companion object {
        val Saver = androidx.compose.runtime.saveable.Saver<SearchFilters, List<Int>>(
            save = { listOf(it.duration.ordinal, it.date.ordinal, it.type.ordinal) },
            restore = { SearchFilters(DurationFilter.entries[it[0]], DateFilter.entries[it[1]], TypeFilter.entries[it[2]]) }
        )

        /** "3 days ago" / "Streamed 2 weeks ago" / ISO date → age in seconds (approximate). */
        fun ageSeconds(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            if (raw.length >= 10 && raw[4] == '-' && raw[7] == '-') {
                val t = runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }.getOrNull()
                    ?: runCatching { java.time.LocalDate.parse(raw.substring(0, 10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()
                    ?: return null
                return java.time.Duration.between(t, java.time.Instant.now()).seconds
            }
            val m = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)""", RegexOption.IGNORE_CASE).find(raw) ?: return null
            val n = m.groupValues[1].toLong()
            return n * when (m.groupValues[2].lowercase()) {
                "second" -> 1L; "minute" -> 60L; "hour" -> 3_600L; "day" -> 86_400L
                "week" -> 604_800L; "month" -> 2_592_000L; else -> 31_536_000L
            }
        }
    }
}

@Composable
private fun SearchFilterBar(f: SearchFilters, onChange: (SearchFilters) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (f.active) item {
            androidx.compose.material3.AssistChip(
                onClick = { onChange(SearchFilters()) },
                label = { Text("Reset") },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Rounded.Close, null, Modifier.size(16.dp)) },
                shape = MaterialTheme.shapes.extraLarge
            )
        }
        item { FilterMenuChip(f.type.label, f.type != TypeFilter.ALL, TypeFilter.entries.map { it.label }) { onChange(f.copy(type = TypeFilter.entries[it])) } }
        item { FilterMenuChip(f.duration.label, f.duration != DurationFilter.ANY, DurationFilter.entries.map { it.label }) { onChange(f.copy(duration = DurationFilter.entries[it])) } }
        item { FilterMenuChip(f.date.label, f.date != DateFilter.ANY, DateFilter.entries.map { it.label }) { onChange(f.copy(date = DateFilter.entries[it])) } }
    }
}

@Composable
private fun FilterMenuChip(label: String, selected: Boolean, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) },
            shape = MaterialTheme.shapes.extraLarge,
            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = Color.White,
                selectedTrailingIconColor = Color.White
            )
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}
