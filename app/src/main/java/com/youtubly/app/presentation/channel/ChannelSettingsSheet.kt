package com.youtubly.app.presentation.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.blur.BodyCoverage
import com.youtubly.app.blur.ChannelRef
import com.youtubly.app.blur.GenderRule
import com.youtubly.app.domain.audio.EqPreset
import com.youtubly.app.playback.VolumeBoost
import com.youtubly.app.presentation.player.formatSpeed

/**
 * Everything that can be set for one channel, in four groups: Playback, Sound, Blur, Feed and downloads.
 * "Default" always means "no choice for this channel: follow the general settings".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChannelSettingsSheet(channel: ChannelRef, onDismiss: () -> Unit, vm: ChannelSettingsViewModel = hiltViewModel()) {
    LaunchedEffect(channel.id) { vm.bind(channel) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val d = ui.d

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::resetAll) { Text("Reset all") }
            }
            Text(
                "These choices apply only to this channel. \"Default\" means: follow the general settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ------------------------------------------------------------ Playback
            Group("Playback")
            var speed by remember(ui.speed) { mutableFloatStateOf(ui.speed ?: 1f) }
            Label("Speed: " + (if (ui.speed == null) "Default" else formatSpeed(ui.speed!!)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = ui.speed == null, onClick = { vm.setSpeed(null) }, label = { Text("Default") })
                Slider(
                    value = speed,
                    onValueChange = { speed = (Math.round(it * 20f) / 20f).coerceIn(0.25f, 3f) },
                    onValueChangeFinished = { vm.setSpeed(speed) },
                    valueRange = 0.25f..3f, steps = 54,
                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                )
            }
            Choice("Open as", listOf<Pair<String, Boolean?>>("Default" to null, "Video" to false, "Audio only" to true), d.audioOnly, vm::setAudioOnly)
            Choice("Quality", listOf<Pair<String, Int?>>("Default" to null, "360p" to 360, "480p" to 480, "720p" to 720, "1080p" to 1080), d.qualityHeight, vm::setQuality)
            Choice("Captions", listOf<Pair<String, String?>>("Default" to null, "Off" to "off", "Arabic" to "ar", "English" to "en"), d.captionLang, vm::setCaption)
            Stepper("Skip intro", d.skipIntroSec, vm::setSkipIntro)
            Stepper("Skip outro", d.skipOutroSec, vm::setSkipOutro)

            // ------------------------------------------------------------ Sound
            Group("Sound")
            Choice(
                "Volume boost",
                listOf<Pair<String, Int?>>("Default" to null) + VolumeBoost.LEVELS_DB.map { (if (it == 0) "Off" else "+$it dB") to it },
                d.volumeBoostDb, vm::setBoost
            )
            Choice(
                "Sound preset",
                listOf<Pair<String, String?>>("Default" to null) + EqPreset.entries.map { it.label to it.name },
                d.soundPreset, vm::setPreset
            )
            Choice(
                "Music remover",
                listOf("Default" to 0, "Never here" to 1, "Always here" to 2),
                if (ui.musicAlways) 2 else if (ui.musicNever) 1 else 0, vm::setMusic
            )

            // ------------------------------------------------------------ Blur
            Group("Blur")
            Choice(
                "Blur this channel",
                listOf("Default" to 0, "Never" to 1, "Always" to 2),
                if (ui.blurNever) 1 else if (ui.blur.force == true) 2 else 0
            ) { mode ->
                vm.setBlurNever(mode == 1)
                vm.setBlurForce(if (mode == 2) true else null)
            }
            Choice(
                "Cover",
                listOf<Pair<String, BodyCoverage?>>("Default" to null, "Face" to BodyCoverage.FACE, "Upper body" to BodyCoverage.UPPER_BODY, "Full body" to BodyCoverage.FULL_BODY),
                ui.blur.coverage, vm::setBlurCoverage
            )
            Choice(
                "Who",
                listOf<Pair<String, GenderRule?>>("Default" to null, "Women" to GenderRule.FEMALE, "Men" to GenderRule.MALE, "Everyone" to GenderRule.BOTH),
                ui.blur.gender, vm::setBlurGender
            )

            // ------------------------------------------------------------ Feed and downloads
            Group("Feed and downloads")
            Choice("Shorts tab", listOf<Pair<String, Boolean?>>("Default" to null, "Show" to true, "Hide" to false), d.showShortsTab, vm::setShortsTab)

            Label("Hidden words (videos of this channel with these words are hidden everywhere)")
            var word by rememberSaveable { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = word, onValueChange = { word = it.take(40) }, singleLine = true,
                    placeholder = { Text("Add a word") }, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { vm.addKeyword(word); word = "" }, enabled = word.isNotBlank()) { Icon(Icons.Rounded.Add, "Add word") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.hiddenKeywords.forEach { k ->
                    InputChip(
                        selected = false, onClick = { vm.removeKeyword(k) }, label = { Text(k) },
                        trailingIcon = { Icon(Icons.Rounded.Close, "Remove", Modifier.padding(0.dp)) }
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Download new videos", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "On Wi-Fi with the battery not low, from now on. Uses the quality or audio-only choice above (720p if none).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = d.autoDownload, onCheckedChange = vm::setAutoDownload, modifier = Modifier.padding(start = 12.dp))
            }
            Choice("Delete downloads once watched", listOf<Pair<String, Boolean?>>("Default" to null, "Yes" to true, "No" to false), d.autoDeleteWatched, vm::setAutoDelete)
        }
    }
}

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

/** A row of chips where exactly one option is selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Label(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (text, value) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(text) })
        }
    }
}

/** Seconds with - and + buttons (5 s steps). */
@Composable
private fun Stepper(label: String, seconds: Int, onChange: (Int) -> Unit) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(seconds - 5) }) { Icon(Icons.Rounded.Remove, "Less") }
        Text(if (seconds <= 0) "Off" else "$seconds s", style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = { onChange(seconds + 5) }) { Icon(Icons.Rounded.Add, "More") }
    }
}
