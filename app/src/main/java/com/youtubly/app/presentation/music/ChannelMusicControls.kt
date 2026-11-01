package com.youtubly.app.presentation.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.blur.ChannelRef
import com.youtubly.app.music.LocalMusicRemover
import kotlinx.coroutines.launch

/**
 * Row for the video long-press sheet, the music-remover twin of "Don't blur this channel":
 * adds/removes the channel on the Music Remover's excluded list (same list as its settings page).
 * Hidden while the music remover is off. Takes effect within a few seconds, even mid-playback.
 */
@Composable
fun ChannelMusicSheetItem(channel: ChannelRef?, onDone: () -> Unit) {
    val remover = LocalMusicRemover.current
    val settings by remover.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (channel == null || !settings.enabled) return
    val excluded = channel.id in settings.whitelist
    Row(
        Modifier.fillMaxWidth().clickable {
            scope.launch {
                if (excluded) remover.store.removeFromWhitelist(channel.id) else remover.store.addToWhitelist(channel)
            }
            onDone()
        }.padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (excluded) Icons.Rounded.MusicNote else Icons.Rounded.MusicOff, null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(20.dp))
        Text(
            if (excluded) "Remove music for this channel again" else "Don't remove music for this channel",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface
        )
    }
}
