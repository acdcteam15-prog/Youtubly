package com.youtubly.app.presentation.music

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.youtubly.app.blur.ChannelRef
import com.youtubly.app.music.Benchmark
import com.youtubly.app.music.LiveStatus
import com.youtubly.app.music.MusicSettings
import com.youtubly.app.presentation.blur.LabeledSlider
import com.youtubly.app.presentation.blur.NavLine
import com.youtubly.app.presentation.blur.SectionCard
import com.youtubly.app.presentation.components.bounceClick
import com.youtubly.app.presentation.components.staggeredEntrance
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicRemoverScreen(onBack: () -> Unit, onOpenWhitelist: () -> Unit, vm: MusicRemoverViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val bench by vm.benchmarks.collectAsStateWithLifecycle()
    val testing by vm.testing.collectAsStateWithLifecycle()
    val original by vm.previewOriginal.collectAsStateWithLifecycle()
    val loop by vm.abLoop.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.message.collect { snack.showSnackbar(it) } }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Music remover") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).nestedScroll(scroll.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = 32.dp + com.youtubly.app.presentation.components.miniBarClearance()),
        ) {
            item { MasterCard(s.enabled, status, vm::setEnabled, Modifier.staggeredEntrance(0)) }
            item {
                val d by vm.debug.collectAsStateWithLifecycle()
                Text(
                    "Audio: ${d.format} · blocks processed ${d.processed} · original ${d.original}" +
                        (d.lastError?.let { "\nLast error: $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 2.dp)
                )
            }

            item {
                SectionCard("Model", Icons.Rounded.Memory, Modifier.staggeredEntrance(1)) {
                    Text("All three are built in, nothing to download.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    vm.models.forEach { m ->
                        ModelRow(m.title, "${m.blurb} · ${m.sizeMb} MB", s.modelId == m.id, bench[m.id], testing == m.id) { vm.setModel(m.id) }
                    }
                    FilledTonalButton(vm::runSpeedTest, enabled = testing == null, modifier = Modifier.fillMaxWidth()) {
                        if (testing != null) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Testing…") }
                        else { Icon(Icons.Rounded.Speed, null); Spacer(Modifier.width(8.dp)); Text("Test speed on this phone") }
                    }
                    Text("Faster than real time is needed to keep up. Aim for 2× or more; video and other apps also need the CPU.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                SectionCard("Music level", Icons.Rounded.GraphicEq, Modifier.staggeredEntrance(2)) {
                    Text("Removal strength", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("Normal", "Strong", "Max").forEachIndexed { i, label ->
                            SegmentedButton(s.strength == i + 1, { vm.setStrength(i + 1) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                        }
                    }
                    Text("Stronger removes more music under speech but can make voices thinner.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LabeledSlider("Music kept", s.musicLevelPct.toFloat(), 0f..MusicSettings.MAX_LEVEL_PCT.toFloat(),
                        { if (it.roundToInt() == 0) "Removed" else "${it.roundToInt()}%" }) { vm.setMusicLevel(it.roundToInt()) }
                    Text("Blending a little music back hides some of the artifacts. Changes apply within a few seconds.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                SectionCard("Where", Icons.Rounded.Tune, Modifier.staggeredEntrance(3)) {
                    NavLine(Icons.Rounded.PersonOff, "Excluded channels", "${s.whitelist.size} channel(s) always keep their music", onOpenWhitelist, badge = s.whitelist.size)
                }
            }

            item {
                SectionCard("Preview lab", Icons.Rounded.Science, Modifier.staggeredEntrance(4)) {
                    Text("Loops 20 seconds of the video that's playing now so you can switch between processed and original sound.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(!original, { vm.setPreviewOriginal(false) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Processed") }
                        SegmentedButton(original, { vm.setPreviewOriginal(true) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Original") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(vm::startPreview) { Icon(Icons.Rounded.Repeat, null); Spacer(Modifier.width(8.dp)); Text("Loop 20 s from here") }
                        AnimatedVisibility(loop.isActive) { OutlinedButton(vm::stopPreview) { Text("Stop loop") } }
                    }
                }
            }

            item {
                SectionCard("Good to know", Icons.Rounded.Info, Modifier.staggeredEntrance(5)) {
                    listOf(
                        "Models are trained on songs, not talks with background music, so expect some voice artifacts.",
                        "The first seconds after starting or seeking may play with music while the first block is prepared.",
                        "If the phone falls behind, playback continues with the original sound instead of stuttering.",
                        "Live processing uses battery and warms the phone.",
                    ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun MasterCard(enabled: Boolean, status: LiveStatus, onChange: (Boolean) -> Unit, modifier: Modifier) {
    val bg by animateColorAsState(if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, tween(400), label = "musicBg")
    val fg by animateColorAsState(if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, tween(400), label = "musicFg")
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(bg)
            .bounceClick(scaleDown = 0.98f) { onChange(!enabled) }.padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (enabled) Icons.Rounded.MusicOff else Icons.Rounded.MusicNote, null, tint = fg, modifier = Modifier.size(32.dp))
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("Remove music", style = MaterialTheme.typography.titleLarge, color = fg)
            Text(statusText(enabled, status), style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.8f))
        }
        Switch(enabled, onChange)
    }
}

private fun statusText(enabled: Boolean, s: LiveStatus): String = when {
    !enabled -> "Off"
    s == LiveStatus.PROCESSING -> "Working on the current video"
    s == LiveStatus.WHITELISTED -> "On · this channel is excluded"
    s == LiveStatus.TOO_SLOW -> "Couldn't keep up on this video · try a lighter model"
    s == LiveStatus.UNSUPPORTED_AUDIO -> "This video's audio format isn't supported"
    else -> "On · voices only in videos"
}

@Composable
private fun ModelRow(title: String, sub: String, selected: Boolean, bench: Benchmark?, testing: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).bounceClick(scaleDown = 0.98f, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            testing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            bench != null -> {
                val (color, label) = when (bench.verdict) {
                    Benchmark.Verdict.GOOD -> MaterialTheme.colorScheme.primary to "Good"
                    Benchmark.Verdict.TIGHT -> MaterialTheme.colorScheme.tertiary to "Tight"
                    Benchmark.Verdict.TOO_SLOW -> MaterialTheme.colorScheme.error to "Too slow"
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("%.1f×".format(bench.realtimeFactor), style = MaterialTheme.typography.labelLarge, color = color)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicWhitelistScreen(onBack: () -> Unit, vm: MusicWhitelistViewModel = hiltViewModel()) {
    val excluded by vm.excluded.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Keep music on"); Text("${excluded.size} channel(s)", style = MaterialTheme.typography.labelMedium) } },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { if (excluded.isNotEmpty()) IconButton({ confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "Clear all") } },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
            item {
                OutlinedTextField(query, { vm.query.value = it }, placeholder = { Text("Search channels") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth().padding(16.dp))
                Text("Videos from these channels play with their original sound.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            item { Header("Excluded") }
            if (excluded.isEmpty()) item {
                Text(if (query.isBlank()) "None yet. Add channels from your subscriptions below." else "No match",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
            items(excluded, key = { "x_" + it.id }) { ch -> ChannelRow(ch) { IconButton({ vm.remove(ch) }) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove") } } }
            if (suggestions.isNotEmpty()) {
                item { Header("From your subscriptions") }
                items(suggestions, key = { "s_" + it.id }) { ch -> ChannelRow(ch) { IconButton({ vm.add(ch) }) { Icon(Icons.Rounded.AddCircleOutline, "Exclude") } } }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Remove music on all channels?") },
        text = { Text("This clears all ${excluded.size} excluded channels.") },
        confirmButton = { TextButton({ confirmClear = false; vm.clearAll() }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable private fun Header(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))

@Composable
private fun ChannelRow(ch: ChannelRef, trailing: @Composable () -> Unit) {
    ListItem(
        leadingContent = {
            if (ch.avatarUrl != null) AsyncImage(ch.avatarUrl, null, Modifier.size(40.dp).clip(CircleShape))
            else Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp).clip(CircleShape)) {
                Box(contentAlignment = Alignment.Center) { Text(ch.name.take(1).uppercase()) }
            }
        },
        headlineContent = { Text(ch.name, maxLines = 1) },
        trailingContent = trailing,
    )
}
