package com.youtubly.app.presentation.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.domain.playback.Transcript
import kotlinx.coroutines.delay

private enum class StudyTab(val label: String) { CHAPTERS("Chapters"), TRANSCRIPT("Transcript"), NOTES("Notes") }

/**
 * One sheet for studying a video: its chapters, a searchable transcript (tap a line to jump there),
 * and your own timestamped notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudySheet(s: PlayerUiState.Success, viewModel: PlayerViewModel, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(if (s.info.chapters.isNotEmpty()) StudyTab.CHAPTERS.name else StudyTab.NOTES.name) }
    val current = StudyTab.valueOf(tab)
    var positionMs by remember { mutableLongStateOf(viewModel.currentPositionMs()) }
    LaunchedEffect(Unit) { while (true) { positionMs = viewModel.currentPositionMs(); delay(500) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudyTab.entries.forEach { t ->
                    FilterChip(selected = t == current, onClick = { tab = t.name }, label = { Text(t.label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (current) {
                    StudyTab.CHAPTERS -> ChaptersTab(s, positionMs, viewModel)
                    StudyTab.TRANSCRIPT -> TranscriptTab(s, positionMs, viewModel)
                    StudyTab.NOTES -> NotesTab(positionMs, viewModel)
                }
            }
        }
    }
}

@Composable
private fun ChaptersTab(s: PlayerUiState.Success, positionMs: Long, viewModel: PlayerViewModel) {
    val chapters = s.info.chapters
    if (chapters.isEmpty()) { Empty("This video has no chapters."); return }
    val currentIdx = chapters.indexOfLast { it.startSeconds * 1000L <= positionMs }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { if (currentIdx > 0) state.scrollToItem((currentIdx - 1).coerceAtLeast(0)) }
    LazyColumn(state = state) {
        itemsIndexed(chapters) { i, ch ->
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.seekToMs(ch.startSeconds * 1000L) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(Transcript.stamp(ch.startSeconds * 1000L), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
                Text(
                    ch.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (i == currentIdx) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == currentIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun TranscriptTab(s: PlayerUiState.Success, positionMs: Long, viewModel: PlayerViewModel) {
    val ui by viewModel.transcript.collectAsStateWithLifecycle()
    val videoId = s.info.video.id
    // Load once per video (the track in use, else the first one).
    LaunchedEffect(videoId) { if (ui.forVideoId != videoId) viewModel.loadTranscript(null) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        if (s.info.captions.size > 1) {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.info.captions) { c ->
                    FilterChip(selected = ui.trackLabel == c.label, onClick = { viewModel.loadTranscript(c) }, label = { Text(c.label) })
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.error != null -> Empty(ui.error!!)
            ui.cues.isEmpty() -> Empty("No transcript yet.")
            else -> {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search the transcript") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } }
                )
                Spacer(Modifier.height(6.dp))
                val rows = remember(ui.cues, query) { Transcript.search(ui.cues, query) }
                val currentCue = Transcript.indexAt(ui.cues, positionMs)
                val state = rememberLazyListState()
                // Follow the video while not searching.
                LaunchedEffect(currentCue, query) {
                    if (query.isBlank() && currentCue >= 0) state.animateScrollToItem((currentCue - 1).coerceAtLeast(0))
                }
                if (rows.isEmpty()) Empty("Nothing matches \"$query\".")
                else LazyColumn(state = state) {
                    items(rows, key = { it.first }) { (index, cue) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.seekToMs(cue.startMs) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(Transcript.stamp(cue.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(56.dp))
                            Text(
                                cue.text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (index == currentCue) FontWeight.Bold else FontWeight.Normal,
                                color = if (index == currentCue) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesTab(positionMs: Long, viewModel: PlayerViewModel) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Write a note about this moment") }, minLines = 2, maxLines = 4
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = { viewModel.addNote(draft); draft = "" }, enabled = draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Add note at ${Transcript.stamp(positionMs)}")
        }
        Spacer(Modifier.height(8.dp))
        if (notes.isEmpty()) Empty("No notes for this video yet.")
        else LazyColumn {
            items(notes, key = { it.id }) { n ->
                Row(Modifier.fillMaxWidth().clickable { viewModel.seekToMs(n.positionMs) }.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Text(Transcript.stamp(n.positionMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
                    Text(n.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { viewModel.deleteNote(n.id) }) { Icon(Icons.Rounded.Delete, "Delete note") }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
