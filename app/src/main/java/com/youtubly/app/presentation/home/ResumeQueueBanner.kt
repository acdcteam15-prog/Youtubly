package com.youtubly.app.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.playback.QueueResume
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ResumeQueueViewModel @Inject constructor(private val queueResume: QueueResume) : ViewModel() {
    val pending = queueResume.pending
    fun resume() = queueResume.resume()
    fun dismiss() = queueResume.dismiss()
}

/** "Continue your queue" card after the app was closed mid-playlist. */
@Composable
fun ResumeQueueBanner(onResume: (url: String, startMs: Long) -> Unit, vm: ResumeQueueViewModel = hiltViewModel()) {
    val q by vm.pending.collectAsStateWithLifecycle()
    AnimatedVisibility(q != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val saved = q ?: return@AnimatedVisibility
        Row(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.PlaylistPlay, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Continue \"${saved.title}\"", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${saved.index + 1} of ${saved.items.size} · ${saved.current?.title.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton({ vm.resume()?.let { (url, ms) -> onResume(url, ms) } }) { Text("Resume") }
            IconButton(vm::dismiss) { Icon(Icons.Rounded.Close, "Dismiss") }
        }
    }
}
