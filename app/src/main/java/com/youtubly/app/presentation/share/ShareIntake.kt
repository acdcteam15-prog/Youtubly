package com.youtubly.app.presentation.share

import com.youtubly.app.presentation.blur.BlurredAsyncImage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.youtubly.app.core.readable
import com.youtubly.app.domain.model.StreamInfoResult
import com.youtubly.app.domain.usecase.GetStreamInfoUseCase
import com.youtubly.app.playback.PlayerController
import com.youtubly.app.presentation.components.DownloadQualitySheet
import com.youtubly.app.presentation.components.LocalVideoActions
import com.youtubly.app.presentation.components.SaveToPlaylistSheet
import com.youtubly.app.presentation.components.shimmer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ShareState {
    data object Loading : ShareState
    data class Ready(val info: StreamInfoResult) : ShareState
    data class Failed(val message: String) : ShareState
}

@HiltViewModel
class ShareIntakeViewModel @Inject constructor(
    private val getStreamInfo: GetStreamInfoUseCase,
    val playerController: PlayerController
) : ViewModel() {
    private val _state = MutableStateFlow<ShareState>(ShareState.Loading)
    val state: StateFlow<ShareState> = _state
    private var loadedUrl: String? = null

    fun load(url: String) {
        if (url == loadedUrl) return
        loadedUrl = url
        _state.value = ShareState.Loading
        viewModelScope.launch {
            getStreamInfo(url).fold(
                onSuccess = { _state.value = ShareState.Ready(it) },
                onFailure = { _state.value = ShareState.Failed(it.readable()) }
            )
        }
    }
}

/**
 * "Share → Youtubly": instead of jumping straight into playback, show the video and let the
 * user choose Play now, Play next, Save to playlist or Download. Links *tapped* in a browser
 * still open directly (that path doesn't come through here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareIntakeSheet(url: String, onPlay: () -> Unit, onDismiss: () -> Unit, viewModel: ShareIntakeViewModel = hiltViewModel()) {
    LaunchedEffect(url) { viewModel.load(url) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = LocalVideoActions.current
    val context = LocalContext.current
    var saveSheet by remember { mutableStateOf(false) }
    var downloadSheet by remember { mutableStateOf(false) }
    fun toast(msg: String) = android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    val ready = state as? ShareState.Ready
    if (saveSheet && ready != null) {
        SaveToPlaylistSheet(ready.info.video, onDismiss = { saveSheet = false; onDismiss() })
        return
    }
    if (downloadSheet && ready != null) {
        DownloadQualitySheet(
            info = ready.info,
            onPick = { option ->
                downloadSheet = false
                actions?.launchUi {
                    val r = actions.queueDownload(ready.info, option)
                    toast(if (r.isSuccess) "Downloading ${option.label}" else "Download failed: ${r.exceptionOrNull()?.message ?: "unknown"}")
                }
                onDismiss()
            },
            onDismiss = { downloadSheet = false; onDismiss() }
        )
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            when (val s = state) {
                ShareState.Loading -> {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large).shimmer())
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(0.8f).height(16.dp).clip(MaterialTheme.shapes.small).shimmer())
                }
                is ShareState.Failed -> Text("Couldn't read this link:\n${s.message}", color = MaterialTheme.colorScheme.error)
                is ShareState.Ready -> {
                    BlurredAsyncImage(
                        s.info.video.thumbnailUrl, null, channel = com.youtubly.app.blur.channelKeyOf(s.info.video.uploaderUrl), contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(s.info.video.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(s.info.video.uploaderName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            ShareAction(Icons.Rounded.PlayArrow, "Play now", enabled = true) { onDismiss(); onPlay() }
            ShareAction(Icons.Rounded.QueuePlayNext, "Play next", enabled = ready != null) {
                val v = ready!!.info.video
                if (viewModel.playerController.playNext(v)) toast("Plays after the current video")
                else onPlay() // nothing playing: just start it
                onDismiss()
            }
            ShareAction(Icons.Rounded.PlaylistAdd, "Save to playlist", enabled = ready != null) { saveSheet = true }
            ShareAction(Icons.Rounded.Download, "Download", enabled = ready != null && actions != null) { downloadSheet = true }
        }
    }
}

@Composable
private fun ShareAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
