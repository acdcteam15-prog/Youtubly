package com.youtubly.app.presentation.editor

import com.youtubly.app.presentation.blur.BlurredAsyncImage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.youtubly.app.domain.editor.EditJob
import com.youtubly.app.domain.editor.EditJobStatus
import com.youtubly.app.domain.editor.ConvertRules
import com.youtubly.app.domain.editor.EditorFormat
import com.youtubly.app.domain.editor.OutputSettings
import com.youtubly.app.domain.editor.SnapshotFormat
import com.youtubly.app.domain.editor.TrimHandle
import com.youtubly.app.domain.editor.TrimRules
import com.youtubly.app.domain.model.DownloadItem
import com.youtubly.app.presentation.components.GradientTitle
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.components.shimmer

/**
 * The Edit tab. Opens a saved video (from Saved → long-press → Edit, or the picker here),
 * trims it with a live preview, and queues MP4 exports that keep running in the background.
 */
@Composable
fun EditorScreen(viewModel: EditorViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val frames by viewModel.frames.collectAsStateWithLifecycle()
    val position by viewModel.positionMs.collectAsStateWithLifecycle()
    val playing by viewModel.isPlaying.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val pendingCut by viewModel.pendingCutMs.collectAsStateWithLifecycle()
    val output by viewModel.output.collectAsStateWithLifecycle()
    val merge by viewModel.merge.collectAsStateWithLifecycle()
    val snapshotFormat by viewModel.snapshotFormat.collectAsStateWithLifecycle()
    val mergeable = candidates.filter { !it.isAudioOnly }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
    // Stop the preview when switching tabs or leaving the app.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) viewModel.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pause()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp + com.youtubly.app.presentation.components.miniBarClearance()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) {
                GradientTitle("Edit", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Trim, cut, merge and compress · saved to Download/Youtubly",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val session = ui.session
        when {
            ui.loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            session != null -> item(key = "session") {
                SessionCard(
                    session = session,
                    viewModel = viewModel,
                    frames = frames,
                    positionMs = position,
                    playing = playing,
                    pendingCutMs = pendingCut,
                    output = output,
                    snapshotFormat = snapshotFormat
                )
            }
            merge != null -> {
                val selected = merge.orEmpty()
                item(key = "merge_title") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Merge videos", Modifier.weight(1f))
                        TextButton(onClick = viewModel::cancelMerge) { Text("Cancel") }
                    }
                }
                item(key = "merge_hint") {
                    Text(
                        "Tap videos in the order they should play. Different sizes are fitted to the first video's frame.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                items(mergeable, key = { "m_${it.id}" }) { item ->
                    val order = selected.indexOf(item.id)
                    MergeRow(
                        item = item,
                        order = order,
                        count = selected.size,
                        onToggle = { viewModel.toggleMerge(item.id) },
                        onUp = { viewModel.moveInMerge(item.id, up = true) },
                        onDown = { viewModel.moveInMerge(item.id, up = false) }
                    )
                }
                item(key = "merge_output") { OutputSettingsCard(output, estimate = null, viewModel = viewModel) }
                item(key = "merge_export") {
                    Button(
                        onClick = viewModel::exportMerge,
                        enabled = selected.size >= 2,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.MergeType, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (selected.size >= 2) "Merge ${selected.size} videos" else "Pick at least 2 videos")
                    }
                }
            }
            else -> {
                ui.error?.let { message ->
                    item(key = "error") { ErrorCard(message, onDismiss = viewModel::dismissError) }
                }
                item(key = "pick_title") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Pick a saved video", Modifier.weight(1f))
                        TextButton(onClick = viewModel::startMerge, enabled = mergeable.size >= 2) {
                            Icon(Icons.Rounded.MergeType, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Merge")
                        }
                    }
                }
                if (candidates.isEmpty()) {
                    item(key = "pick_empty") {
                        Text(
                            "Nothing to edit yet. Download something, then long-press it in Saved and choose Edit — or share a video to Youtubly from any app.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                } else {
                    items(candidates, key = { "c_${it.id}" }) { item -> CandidateRow(item) { viewModel.open(item.id) } }
                }
            }
        }

        if (jobs.isNotEmpty()) {
            item(key = "jobs_title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Exports", Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearFinished, enabled = jobs.any { !it.status.isActive }) { Text("Clear finished") }
                }
            }
            items(jobs, key = { "j_${it.id}" }) { job ->
                JobRow(
                    job = job,
                    onCancel = { viewModel.cancelJob(job.id) },
                    onRetry = { viewModel.retryJob(job.id) },
                    onRemove = { viewModel.removeJob(job.id) },
                    onOpen = { job.outputUri?.let { openVideo(context, it) } },
                    onShare = { job.outputUri?.let { shareVideo(context, it) } }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun SessionCard(
    session: EditorSession,
    viewModel: EditorViewModel,
    frames: List<android.graphics.Bitmap?>,
    positionMs: Long,
    playing: Boolean,
    pendingCutMs: Long?,
    output: OutputSettings,
    snapshotFormat: SnapshotFormat
) {
    val audioOnly = session.source.audioOnly
    val trim = session.trim
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                session.source.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = viewModel::close) { Icon(Icons.Rounded.Close, "Close video") }
        }

        // Tablets / landscape: preview on the left, controls on the right, so the timeline
        // and export buttons stay on screen instead of being pushed down by a huge preview.
        if (com.youtubly.app.presentation.components.LocalWideLayout.current) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1.15f)) { EditorPreview(session, viewModel, playing) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditorControls(session, viewModel, frames, positionMs, pendingCutMs, output, snapshotFormat)
                }
            }
        } else {
            EditorPreview(session, viewModel, playing)
            EditorControls(session, viewModel, frames, positionMs, pendingCutMs, output, snapshotFormat)
        }
    }
}

@Composable
private fun ColumnScope.EditorPreview(session: EditorSession, viewModel: EditorViewModel, playing: Boolean) {
    val audioOnly = session.source.audioOnly
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = viewModel.player
                    }
                },
                update = { view -> if (view.player !== viewModel.player) view.player = viewModel.player },
                onRelease = { it.player = null }
            )
            if (audioOnly) {
                // No picture to show: the download's thumbnail (if any) with a headphones badge.
                BlurredAsyncImage(session.source.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                Icon(
                    Icons.Rounded.Headphones, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(22.dp)
                )
            }
            // Tap target above the video view (the view itself doesn't forward taps to Compose).
            Box(Modifier.fillMaxSize().clickable(onClick = viewModel::togglePlay))
            this@EditorPreview.AnimatedVisibility(visible = !playing, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.PlayArrow, "Play selection", tint = Color.White, modifier = Modifier.size(32.dp)) }
            }
        }
}

@Composable
private fun ColumnScope.EditorControls(
    session: EditorSession,
    viewModel: EditorViewModel,
    frames: List<android.graphics.Bitmap?>,
    positionMs: Long,
    pendingCutMs: Long?,
    output: OutputSettings,
    snapshotFormat: SnapshotFormat
) {
    val audioOnly = session.source.audioOnly
    val trim = session.trim
        if (viewModel.needsConversion(session)) {
            // One tap: whole file → MP4 (or M4A for audio), nothing else changed.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "This file is ${ConvertRules.formatLabel(session.source.path)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = viewModel::convertWholeFile) {
                    Icon(Icons.Rounded.Transform, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (audioOnly) "Convert to M4A" else "Convert to MP4")
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                EditorFormat.clock(positionMs, tenths = true),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Result ${EditorFormat.clock(session.keptMs, tenths = true)} of ${EditorFormat.clock(session.source.durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TrimTimeline(
            durationMs = session.source.durationMs,
            trim = trim,
            positionMs = positionMs,
            frames = frames,
            onHandleDrag = viewModel::onHandleDrag,
            onHandleDragEnd = viewModel::onHandleDragEnd,
            onSeek = viewModel::seekTo,
            cuts = session.cuts,
            pendingCutMs = pendingCutMs
        )

        HandleRow("Start", trim.startMs, TrimHandle.START, viewModel)
        HandleRow("End", trim.endMs, TrimHandle.END, viewModel)

        // Delete a part: two marks at the playhead.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (pendingCutMs == null) "Remove a part" else "From ${EditorFormat.clock(pendingCutMs, tenths = true)} to…",
                style = MaterialTheme.typography.labelLarge,
                color = if (pendingCutMs == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            if (pendingCutMs != null) TextButton(onClick = viewModel::cancelCutMark) { Text("Cancel") }
            androidx.compose.material3.OutlinedButton(onClick = viewModel::markCut) {
                Icon(Icons.Rounded.ContentCut, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (pendingCutMs == null) "Cut from here" else "…to here")
            }
        }
        session.cuts.forEachIndexed { i, c ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Removed ${EditorFormat.clock(c.startMs, tenths = true)}–${EditorFormat.clock(c.endMs, tenths = true)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.removeCut(i) }) {
                    Icon(Icons.Rounded.Close, "Keep this part", tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        if (!audioOnly) {
            // Snapshot: exact frame under the playhead → Pictures/Youtubly.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Snapshot", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                SnapshotFormat.entries.forEach { f ->
                    TextButton(
                        onClick = { viewModel.setSnapshotFormat(f) },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            f.name,
                            color = if (f == snapshotFormat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = if (f == snapshotFormat) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium
                        )
                    }
                }
                androidx.compose.material3.OutlinedButton(onClick = viewModel::takeSnapshot) {
                    Icon(Icons.Rounded.PhotoCamera, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save frame")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sound only", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                androidx.compose.material3.OutlinedButton(onClick = viewModel::extractAudio) {
                    Icon(Icons.Rounded.MusicNote, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Extract audio (.m4a)")
                }
            }
            OutputSettingsCard(output, estimate = viewModel.estimateBytes(session, output), viewModel = viewModel)
        } else {
            Text(
                "Audio edit · saved as .m4a (AAC)" + (viewModel.estimateBytes(session, output).takeIf { it > 0 }?.let { " · ≈ ${EditorFormat.size(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::undo, enabled = session.canUndo) { Icon(Icons.Rounded.Undo, "Undo") }
            IconButton(onClick = viewModel::redo, enabled = session.canRedo) { Icon(Icons.Rounded.Redo, "Redo") }
            TextButton(onClick = viewModel::resetEdits, enabled = !session.isUnchanged) { Text("Reset") }
            Spacer(Modifier.weight(1f))
            Button(onClick = viewModel::export, enabled = viewModel.canExport(session, output)) {
                Icon(Icons.Rounded.ContentCut, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Export")
            }
        }
        Text(
            when {
                audioOnly && session.isUnchanged -> "Drag the handles or remove parts."
                audioOnly -> "Saves a new .m4a — the original stays in Saved."
                session.isUnchanged && output.isOriginal -> "Drag the handles, remove parts, or pick a new size."
                session.keep.size > 1 || !output.isOriginal -> "Saves a new MP4 (re-encoded, takes a while) — the original stays in Saved."
                else -> "Saves a new MP4 — the original stays in Saved."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
}

@Composable
private fun HandleRow(label: String, ms: Long, handle: TrimHandle, viewModel: EditorViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(44.dp))
        Text(
            EditorFormat.clock(ms, tenths = true),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        val tight = PaddingValues(horizontal = 8.dp)
        TextButton(onClick = { viewModel.nudge(handle, -TrimRules.NUDGE_MS) }, contentPadding = tight) { Text("−0.1") }
        TextButton(onClick = { viewModel.nudge(handle, TrimRules.NUDGE_MS) }, contentPadding = tight) { Text("+0.1") }
        TextButton(onClick = { viewModel.setHandleAtPlayhead(handle) }, contentPadding = tight) { Text("Set here") }
    }
}

/** Advanced output controls: resolution (never upscales) and video bitrate. */
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OutputSettingsCard(output: OutputSettings, estimate: Long?, viewModel: EditorViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Output · MP4 (H.264 + AAC)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Text("Resolution (height, never upscaled)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            androidx.compose.material3.FilterChip(
                selected = output.maxHeight == null,
                onClick = { viewModel.setMaxHeight(null) },
                label = { Text("Original") }
            )
            OutputSettings.HEIGHT_CHOICES.forEach { h ->
                androidx.compose.material3.FilterChip(
                    selected = output.maxHeight == h,
                    onClick = { viewModel.setMaxHeight(h) },
                    label = { Text("${h}p") }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Video bitrate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    output.videoBitrateKbps?.let { EditorFormat.bitrate(it) } ?: "Auto (encoder chooses)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text("Custom", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Switch(
                checked = output.videoBitrateKbps != null,
                onCheckedChange = { on -> viewModel.setVideoBitrate(if (on) DEFAULT_CUSTOM_KBPS else null) }
            )
        }
        output.videoBitrateKbps?.let { kbps ->
            androidx.compose.material3.Slider(
                value = kbps.toFloat(),
                onValueChange = { v -> viewModel.setVideoBitrate((v / 100).roundToInt() * 100) },
                valueRange = OutputSettings.MIN_KBPS.toFloat()..OutputSettings.MAX_KBPS.toFloat()
            )
        }
        if (estimate != null && estimate > 0) {
            Text(
                "Estimated size ≈ ${EditorFormat.size(estimate)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val DEFAULT_CUSTOM_KBPS = 2_000

@Composable
private fun MergeRow(
    item: DownloadItem,
    order: Int,
    count: Int,
    onToggle: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    val selected = order >= 0
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .bounceClick(scaleDown = 0.98f, onClick = onToggle)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Text("${order + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(80.dp).height(45.dp).clip(RoundedCornerShape(8.dp)).shimmer()) {
            BlurredAsyncImage(item.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            IconButton(onClick = onUp, enabled = order > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Earlier") }
            IconButton(onClick = onDown, enabled = order < count - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "Later") }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.width(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

@Composable
private fun CandidateRow(item: DownloadItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .bounceClick(scaleDown = 0.98f, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(10.dp)).shimmer()) {
            BlurredAsyncImage(item.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            if (item.isAudioOnly) Icons.Rounded.Headphones else Icons.Rounded.ContentCut, "Edit",
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun JobRow(
    job: EditJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(job.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    job.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when (job.status) {
                EditJobStatus.QUEUED, EditJobStatus.RUNNING ->
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel export") }
                EditJobStatus.COMPLETED -> {
                    IconButton(onClick = onOpen) { Icon(Icons.Rounded.PlayArrow, "Open") }
                    IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Share") }
                    IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove from list") }
                }
                EditJobStatus.FAILED, EditJobStatus.CANCELLED -> {
                    IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Retry") }
                    IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove from list") }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        when (job.status) {
            EditJobStatus.QUEUED -> StatusLine(Icons.Rounded.Schedule, "Waiting in queue", MaterialTheme.colorScheme.onSurfaceVariant)
            EditJobStatus.RUNNING -> Column(Modifier.padding(end = 8.dp)) {
                LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
                Spacer(Modifier.height(4.dp))
                Text("Exporting · ${job.progress}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditJobStatus.COMPLETED -> StatusLine(Icons.Rounded.CheckCircle, "Saved · ${job.outputName ?: "Download/Youtubly"}", MaterialTheme.colorScheme.tertiary)
            EditJobStatus.FAILED -> StatusLine(Icons.Rounded.ErrorOutline, job.error ?: "Failed", MaterialTheme.colorScheme.error)
            EditJobStatus.CANCELLED -> StatusLine(Icons.Rounded.Close, "Cancelled", MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun openVideo(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
        .onFailure { Toast.makeText(context, "No app can open this video", Toast.LENGTH_SHORT).show() }
}

private fun shareVideo(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share video")) }
        .onFailure { Toast.makeText(context, "Couldn't share this video", Toast.LENGTH_SHORT).show() }
}
