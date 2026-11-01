package com.youtubly.app.presentation.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.youtubly.app.core.ShareLinks
import kotlin.math.roundToInt

/** Opens Android's share sheet with plain text. */
fun shareText(context: Context, text: String, subject: String? = null, chooserTitle: String = "Share") {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
    }
    runCatching { context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Shares a downloaded file through the app's FileProvider (…/files/Youtubly/). */
fun shareFile(context: Context, path: String, audio: Boolean, title: String) {
    val uri = runCatching {
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(path))
    }.getOrElse { Toast.makeText(context, "Couldn't share this file", Toast.LENGTH_SHORT).show(); return }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = if (audio) "audio/*" else "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share file").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private enum class StartFrom { BEGINNING, CUSTOM, LOOP }

/**
 * Share sheet for a video link: always cleaned (no si=/pp= tracking), short or full link,
 * start from the beginning / a chosen time / the A–B loop. The link preview updates live.
 *
 * @param positionSec where playback is now (the custom time starts here)
 * @param loop A–B loop in seconds, if one is set
 * @param onOpened / onClosed let the player pause while the sheet is open and resume after
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareVideoSheet(
    url: String,
    title: String,
    positionSec: Int,
    durationSec: Int,
    loop: Pair<Int, Int>? = null,
    onOpened: () -> Unit = {},
    onClosed: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var short by rememberSaveable { mutableStateOf(true) }
    var from by remember { mutableStateOf(if (positionSec > 5) StartFrom.CUSTOM else StartFrom.BEGINNING) }
    var start by remember { mutableIntStateOf(positionSec.coerceAtLeast(0)) }
    val maxSec = durationSec.coerceAtLeast(start + 1)
    DisposableEffect(Unit) { onOpened(); onDispose { onClosed() } }

    val startSec = when (from) { StartFrom.BEGINNING -> 0; StartFrom.CUSTOM -> start; StartFrom.LOOP -> loop?.first ?: 0 }
    val link = ShareLinks.build(url, short, startSec)
    val message = if (from == StartFrom.LOOP && loop != null) ShareLinks.loopMessage(link, loop.first, loop.second) else link

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Share", style = MaterialTheme.typography.titleLarge)
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("Link", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(short, { short = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Short (youtu.be)") }
                SegmentedButton(!short, { short = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Full") }
            }

            Text("Start from", style = MaterialTheme.typography.labelLarge)
            val options = buildList { add(StartFrom.BEGINNING); add(StartFrom.CUSTOM); if (loop != null) add(StartFrom.LOOP) }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, o ->
                    SegmentedButton(from == o, { from = o }, SegmentedButtonDefaults.itemShape(i, options.size)) {
                        Text(when (o) { StartFrom.BEGINNING -> "Start"; StartFrom.CUSTOM -> ShareLinks.clock(start); StartFrom.LOOP -> "A–B loop" })
                    }
                }
            }
            if (from == StartFrom.CUSTOM) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-10, -1).forEach { d -> OutlinedButton({ start = (start + d).coerceIn(0, maxSec) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("${d}s") } }
                    Text(ShareLinks.clock(start), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    listOf(1, 10).forEach { d -> OutlinedButton({ start = (start + d).coerceIn(0, maxSec) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+${d}s") } }
                }
                Slider(start.toFloat(), { start = it.roundToInt() }, valueRange = 0f..maxSec.toFloat())
            }
            if (from == StartFrom.LOOP && loop != null) {
                Text("YouTube links can't stop at a time, so the link starts at ${ShareLinks.clock(loop.first)} and the message says where to stop.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
                Text(message, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({
                    clipboard.setText(AnnotatedString(message))
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show(); onDismiss()
                }, Modifier.weight(1f)) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy") }
                Button({ shareText(context, message, title, "Share video"); onDismiss() }, Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Share")
                }
            }
        }
    }
}
