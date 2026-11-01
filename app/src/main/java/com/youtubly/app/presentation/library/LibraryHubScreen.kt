package com.youtubly.app.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youtubly.app.presentation.downloads.DownloadsScreen
import com.youtubly.app.presentation.playlists.PlaylistsScreen

/** Which page of the Library tab is open. A holder object so the widget / deep links can open Playlists directly. */
object LibraryHubState {
    var page by mutableIntStateOf(0)
    const val DOWNLOADS = 0
    const val PLAYLISTS = 1
}

/** One bottom-bar tab for everything you keep: downloaded videos and playlists, switched with two chips. */
@Composable
fun LibraryHubScreen(
    onOpenAudio: () -> Unit,
    onOpenLocal: (String) -> Unit,
    onOpenEditor: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenYoutubePlaylist: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = LibraryHubState.page == LibraryHubState.DOWNLOADS,
                onClick = { LibraryHubState.page = LibraryHubState.DOWNLOADS },
                label = { Text("Downloads") },
                leadingIcon = { Icon(Icons.Rounded.DownloadForOffline, null) }
            )
            FilterChip(
                selected = LibraryHubState.page == LibraryHubState.PLAYLISTS,
                onClick = { LibraryHubState.page = LibraryHubState.PLAYLISTS },
                label = { Text("Playlists") },
                leadingIcon = { Icon(Icons.Rounded.PlaylistPlay, null) }
            )
        }
        Box(Modifier.weight(1f)) {
            if (LibraryHubState.page == LibraryHubState.DOWNLOADS) {
                DownloadsScreen(onOpen = onOpenLocal, onOpenEditor = onOpenEditor, onOpenAudio = onOpenAudio, embedded = true)
            } else {
                PlaylistsScreen(
                    onBack = {}, onOpen = onOpenPlaylist, onOpenYoutubePlaylist = onOpenYoutubePlaylist,
                    showBack = false, embedded = true
                )
            }
        }
    }
}
