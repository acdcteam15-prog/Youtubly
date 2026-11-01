package com.youtubly.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.youtubly.app.domain.model.DownloadOption
import com.youtubly.app.domain.model.DownloadOptions
import com.youtubly.app.domain.model.StreamInfoResult

/** Lists every single-file stream (muxed video+audio, then audio bitrates) to download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQualitySheet(info: StreamInfoResult, onPick: (DownloadOption) -> Unit, onDismiss: () -> Unit) {
    val options = remember(info) { DownloadOptions.build(info) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                "Download",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Text(
                info.video.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)
            )
            val video = options.filter { !it.audioOnly }
            val audio = options.filter { it.audioOnly }
            if (options.isEmpty()) {
                Text("No downloadable streams for this video.", modifier = Modifier.padding(24.dp))
            }
            if (video.isNotEmpty()) Header("Video")
            video.forEach { OptionRow(it, onPick) }
            if (audio.isNotEmpty()) Header("Audio only")
            audio.forEach { OptionRow(it, onPick) }
            val why = remember(info) { DownloadOptions.hdDiagnostic(info) }
            if (why != null) android.util.Log.w("Youtubly", "No HD download option: $why")
            Text(
                if (why == null) "HD options are saved as two separate streams that are joined into one file on your phone (no re-encoding)."
                else "No HD option for this video. $why",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            Spacer(Modifier.navigationBarsPadding().height(4.dp))
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun OptionRow(option: DownloadOption, onPick: (DownloadOption) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(option) }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (option.audioOnly) Icons.Rounded.Headphones else Icons.Rounded.Videocam,
            null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surface)
        )
        Spacer(Modifier.width(16.dp))
        Text(option.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
