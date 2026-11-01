package com.youtubly.app.presentation.blur

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.blur.ChannelRef
import com.youtubly.app.blur.LocalBlurEngine
import kotlinx.coroutines.launch

data class ChannelBlurState(val excluded: Boolean, val blurEnabled: Boolean, val toggle: () -> Unit)

@Composable
fun rememberChannelBlurState(channel: ChannelRef?): ChannelBlurState {
    val engine = LocalBlurEngine.current
    val settings by engine.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val excluded = channel != null && channel.id in settings.whitelist
    return ChannelBlurState(
        excluded = excluded,
        blurEnabled = settings.enabled && channel != null,
        toggle = {
            if (channel != null) scope.launch {
                if (excluded) engine.store.removeFromWhitelist(channel.id) else engine.store.addToWhitelist(channel)
            }
        },
    )
}

/** Row for the video long-press sheet — same look as the sheet's other items. */
@Composable
fun ChannelBlurSheetItem(channel: ChannelRef?, onDone: () -> Unit) {
    val st = rememberChannelBlurState(channel)
    if (!st.blurEnabled) return
    Row(
        Modifier.fillMaxWidth().clickable { st.toggle(); onDone() }.padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (st.excluded) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(20.dp))
        Text(if (st.excluded) "Blur this channel again" else "Don't blur this channel",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Per-video override, shown under the channel item. Hidden while blur is off. */
@Composable
fun VideoBlurSheetItem(videoId: String, onDone: () -> Unit) {
    val engine = LocalBlurEngine.current
    val settings by engine.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (!settings.enabled) return
    val excluded = videoId in settings.excludedVideos
    Row(
        Modifier.fillMaxWidth().clickable {
            scope.launch { engine.store.setVideoExcluded(videoId, !excluded) }; onDone()
        }.padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (excluded) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(20.dp))
        Text(if (excluded) "Blur this video again" else "Don't blur this video",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Chip for the channel page header; the icon/label morph when toggled. */
@Composable
fun ChannelBlurChip(channel: ChannelRef?, modifier: Modifier = Modifier) {
    val st = rememberChannelBlurState(channel)
    if (!st.blurEnabled) return
    FilterChip(
        selected = st.excluded, onClick = st.toggle, modifier = modifier,
        label = {
            AnimatedContent(st.excluded, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "chipLabel") {
                Text(if (it) "Not blurred" else "Faces blurred")
            }
        },
        leadingIcon = {
            AnimatedContent(st.excluded, transitionSpec = {
                (scaleIn(spring(dampingRatio = 0.5f)) + fadeIn()) togetherWith (scaleOut() + fadeOut())
            }, label = "chipIcon") {
                Icon(if (it) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, null, Modifier.size(18.dp))
            }
        },
    )
}
