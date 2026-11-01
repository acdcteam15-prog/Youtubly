package com.youtubly.app.presentation.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.youtubly.app.domain.download.CleanerGroup
import com.youtubly.app.domain.download.sizeBytes

/**
 * Storage cleaner: suggestions (duplicates, watched, old and never watched, biggest files) with
 * check boxes. Nothing is pre-selected; deleting asks for confirmation first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageCleanerSheet(groups: List<CleanerGroup>, onDelete: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val all = remember(groups) { groups.flatMap { it.items }.associateBy { it.id } }
    // Drop selections that vanished (deleted elsewhere).
    val live = selected.filter { it in all }.toSet()
    val bytes = live.sumOf { all[it]?.sizeBytes() ?: 0L }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("Free up space", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                "Tick what to remove. Nothing is deleted until you confirm.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
            if (groups.isEmpty()) {
                Text("Nothing to clean up right now.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                groups.forEach { g ->
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${g.title} · ${formatBytes(g.bytes)}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(g.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val ids = g.items.map { it.id }.toSet()
                        val allOn = ids.all { it in live }
                        TextButton(onClick = { selected = if (allOn) live - ids else live + ids }) { Text(if (allOn) "None" else "All") }
                    }
                    g.items.forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = if (item.id in live) live - item.id else live + item.id }
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = item.id in live, onCheckedChange = { selected = if (it) live + item.id else live - item.id })
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOfNotNull(item.qualityLabel, if (item.isAudioOnly) "audio" else null).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(formatBytes(item.sizeBytes()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(
                onClick = { confirm = true },
                enabled = live.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                androidx.compose.material3.Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (live.isEmpty()) "Select items to delete" else "Delete ${live.size} · free ${formatBytes(bytes)}")
            }
        }
    }

    if (confirm && live.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${live.size} download${if (live.size == 1) "" else "s"}?") },
            text = { Text("This frees ${formatBytes(bytes)} and can't be undone.") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete(live); selected = emptySet(); onDismiss() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}
