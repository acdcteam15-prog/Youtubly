package com.youtubly.app.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width of the side navigation rail shown in the wide layout. */
val NavRailWidth: Dp = 80.dp

/**
 * Columns for a video feed: 1 on phones (layout unchanged), otherwise as many ≥[minCell]
 * cells as fit beside the navigation rail.
 */
@Composable
fun rememberFeedColumns(minCell: Dp = 320.dp): Int {
    if (!LocalWideLayout.current) return 1
    val width = LocalConfiguration.current.screenWidthDp.dp - NavRailWidth
    return (width / minCell).toInt().coerceAtLeast(1)
}

/**
 * Puts [videos] in a LazyColumn as a grid of [columns] cells per row. With one column it is
 * exactly the old `itemsIndexed` (phones don't change); with more it emits one row item per
 * chunk, so screens keep their LazyColumn, list state, sticky headers and load-more logic.
 */
fun <T> LazyListScope.adaptiveItems(
    videos: List<T>,
    columns: Int,
    key: (index: Int, item: T) -> Any,
    cell: @Composable LazyItemScope.(index: Int, item: T) -> Unit
) {
    if (columns <= 1) {
        itemsIndexed(videos, key = key) { i, v -> cell(i, v) }
        return
    }
    val rows = videos.withIndex().chunked(columns)
    items(rows, key = { row -> "row_" + key(row.first().index, row.first().value).toString() }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.Top
        ) {
            row.forEach { (i, v) -> Box(Modifier.weight(1f)) { cell(i, v) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
