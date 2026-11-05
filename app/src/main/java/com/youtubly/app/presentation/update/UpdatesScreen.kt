package com.youtubly.app.presentation.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youtubly.app.update.ReleaseInfo
import com.youtubly.app.update.UpdateParser
import com.youtubly.app.update.UpdateState

/** Settings → App updates: check GitHub for a newer build, read what's new, download and install it. */
@Composable
fun UpdatesScreen(onBack: () -> Unit, vm: UpdatesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val auto by vm.autoCheck.collectAsStateWithLifecycle()
    val savedToken by vm.token.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var askAllow by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var tokenText by rememberSaveable(savedToken) { mutableStateOf(savedToken) }

    // Opening the screen counts as asking: look right away (unless a result is already on screen).
    val installFailure by com.youtubly.app.update.UpdateInstallResult.failure.collectAsStateWithLifecycle()
    val autoDownload by vm.autoDownload.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.restoreReady(); if (state is UpdateState.Idle) vm.check() }
    // The download finished: hand it to Android's installer (it shows its own confirmation).
    LaunchedEffect(state) {
        val s = state
        if (s is UpdateState.Ready) {
            if (vm.canInstall()) vm.install(s.file) else askAllow = true
        }
    }

    fun install(info: ReleaseInfo) { if (vm.canInstall()) vm.download(info) else askAllow = true }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("App updates", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Youtubly ${vm.currentVersion}", style = MaterialTheme.typography.titleMedium)
                    Text("Build ${vm.currentBuild}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Updates come from github.com/${vm.repo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            when (val s = state) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                    Text("Checking for updates…")
                }
                is UpdateState.UpToDate -> Text("Youtubly ${s.versionName} is up to date.", style = MaterialTheme.typography.bodyLarge)
                is UpdateState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = vm::check) { Text("Try again") }
                }
                is UpdateState.Available -> AvailableCard(
                    info = s.info,
                    action = { Button(onClick = { install(s.info) }, modifier = Modifier.fillMaxWidth()) { Text("Download and install") } },
                    secondary = if (!s.info.required) {
                        { OutlinedButton(onClick = { vm.skip(s.info) }, modifier = Modifier.fillMaxWidth()) { Text("Skip this version") } }
                    } else null
                )
                is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Downloading ${s.info.versionName}…", style = MaterialTheme.typography.titleSmall)
                    if (s.fraction >= 0f) {
                        LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${(s.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("It keeps going with the screen off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is UpdateState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ready to install ${s.info.versionName}.", style = MaterialTheme.typography.titleSmall)
                    Button(onClick = { if (vm.canInstall()) vm.install(s.file) else askAllow = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Install")
                    }
                }
            }

            installFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (state !is UpdateState.Checking && state !is UpdateState.Downloading) {
                OutlinedButton(onClick = vm::check, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check automatically", style = MaterialTheme.typography.bodyLarge)
                    Text("Looks twice a day and tells you when a new build is out.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = auto, onCheckedChange = vm::setAuto, modifier = Modifier.padding(start = 12.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Download updates on Wi-Fi", style = MaterialTheme.typography.bodyLarge)
                    Text("The new build is fetched in the background, so installing it is one tap.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoDownload, onCheckedChange = vm::setAutoDownload, modifier = Modifier.padding(start = 12.dp))
            }

            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
            if (advanced) {
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it; vm.setToken(it) },
                    label = { Text("GitHub token (private repository only)") },
                    supportingText = { Text("Create a fine-grained token with read access to Contents of this repository. Leave empty for a public repository.") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (askAllow) {
        AlertDialog(
            onDismissRequest = { askAllow = false },
            title = { Text("Allow installing from Youtubly") },
            text = { Text("Android asks you once before an app may install updates for itself. Open settings, switch on “Allow from this source”, then come back and tap Download and install again.") },
            confirmButton = { TextButton(onClick = { askAllow = false; context.startActivity(vm.allowInstallIntent()) }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { askAllow = false; vm.reset() }) { Text("Not now") } }
        )
    }
}

@Composable
private fun AvailableCard(info: ReleaseInfo, action: @Composable () -> Unit, secondary: (@Composable () -> Unit)?) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Update available: ${info.versionName} (build ${info.build})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            val size = UpdateParser.humanSize(info.sizeBytes)
            if (info.patchSize > 0) {
                Text(
                    "Small update: ${UpdateParser.humanSize(info.patchSize)} (the full file is $size)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else if (size.isNotEmpty()) Text(size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (info.required) Text("This update is required.", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            if (info.notes.isNotBlank()) {
                Text("What is new", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(info.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            action()
            secondary?.invoke()
        }
    }
}
