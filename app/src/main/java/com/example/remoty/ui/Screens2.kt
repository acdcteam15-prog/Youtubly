package com.example.remoty.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remoty.Inspect
import com.example.remoty.BuildConfig
import com.example.remoty.RemotyVm
import com.example.remoty.ZtManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(vm: RemotyVm, canLock: Boolean, onPickZip: () -> Unit) {
    BackHandler(enabled = vm.editorPath != null || vm.openJobId != null || vm.picking || vm.overlay != null) {
        when {
            vm.overlay != null -> vm.overlay = null
            vm.editorPath != null -> vm.closeEditor()
            vm.openJobId != null -> vm.closeJob()
            else -> vm.picking = false
        }
    }
    when {
        vm.overlay == "remote" -> RemoteScreen(vm)
        vm.overlay == "audit" -> AuditScreen(vm)
        vm.editorPath != null -> EditorScreen(vm)
        vm.openJobId != null -> JobScreen(vm)
        vm.picking -> FileBrowser(vm, pick = true)
        else -> Scaffold(
            containerColor = Brand.BgDeep,
            topBar = { StatusBar(vm) },
            bottomBar = {
                NavigationBar(containerColor = Brand.Header) {
                    listOf("🛠" to "Build", "📁" to "Files", "📋" to "Jobs", "💻" to "Laptop").forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(
                            selected = vm.tab == i, onClick = { vm.tab = i },
                            icon = { Text(icon, fontSize = 20.sp) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Brand.TrackDark),
                        )
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                when (vm.tab) {
                    0 -> BuildTab(vm, onPickZip)
                    1 -> FileBrowser(vm, pick = false)
                    2 -> JobsTab(vm)
                    else -> LaptopTab(vm, canLock)
                }
            }
        }
    }
}

@Composable
private fun StatusBar(vm: RemotyVm) {
    Row(Modifier.fillMaxWidth().background(Brand.Header).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(if (vm.online) Brand.StatusGreen else Brand.StatusRed)
        Text(" ${vm.active?.name ?: ""}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        val running = vm.jobs.count { it.active }
        if (running > 0) Text("$running running  ", color = Brand.StatusBlue, fontSize = 13.sp)
        Text(if (vm.online) "${vm.latency} ms" else "offline", color = Brand.TextSecondary, fontSize = 13.sp)
    }
}

// ------------------------------------------------------------------ Build tab
@Composable
fun BuildTab(vm: RemotyVm, onPickZip: () -> Unit) {
    val sheet = vm.sheet
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Update a project", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Panel {
            Text("1. Project folder on the laptop", fontWeight = FontWeight.SemiBold)
            Text(if (vm.formFolder.isBlank()) "Not chosen yet" else vm.formFolder, color = if (vm.formFolder.isBlank()) Brand.TextSecondary else Brand.AccentBlue)
            OutlinedButton(onClick = { vm.startPicking() }) { Text("Browse folders...") }
            if (vm.favorites.isNotEmpty()) {
                Hint("Saved folders")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.favorites.forEach { f ->
                        AssistChip(onClick = { vm.formFolder = f }, label = { Text("⭐ " + f.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/')) })
                    }
                }
            }
        }
        Panel {
            Text("2. The zip from your chat", fontWeight = FontWeight.SemiBold)
            val z = vm.zip
            if (z == null) {
                Hint("Pick it from Downloads, or in your chat app or browser use Share, then Remoty.")
                Button(onClick = onPickZip, enabled = !vm.zipBusy) { Text("Pick a zip file") }
            } else {
                Text("📦 ${z.name}", fontWeight = FontWeight.SemiBold)
                Hint(String.format("%.1f MB", z.size / 1048576.0))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickZip, enabled = !vm.zipBusy) { Text("Pick another") }
                    TextButton(onClick = { vm.clearZip() }) { Text("Remove") }
                }
            }
        }
        if (vm.zipBusy) {
            Text(vm.zipStatus, fontSize = 14.sp)
            if (vm.zipProgress > 0f) LinearProgressIndicator(progress = { vm.zipProgress }, modifier = Modifier.fillMaxWidth())
        }
        GradientButton(
            "📤  Send the zip and check it", Brand.BuildBrush,
            enabled = vm.online && vm.zip != null && vm.formFolder.isNotBlank() && !vm.zipBusy,
        ) { vm.checkZip() }
        OutlinedButton(
            onClick = { vm.checkFolderScripts() }, enabled = vm.online && vm.formFolder.isNotBlank() && !vm.zipBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Or run a script that is already in the folder") }
        TextButton(onClick = { vm.overlay = "cloud" }) { Text("☁  Laptop off? Build on GitHub instead") }
        Hint("Nothing runs until you tap Run on the next screen. The zip is unzipped over the folder, like you do on the PC, and your other files stay.")
        if (!vm.online) Hint("The laptop is offline: check that Remoty Agent is running.")
    }
    if (sheet != null) ReadySheet(vm, sheet)
}

@Composable
private fun ReadySheet(vm: RemotyVm, s: Inspect) {
    var chosen by remember(s) { mutableStateOf(s.defaultScript ?: "") }
    val hasZip = s.uploadId != null
    AlertDialog(
        onDismissRequest = { vm.cancelSheet() },
        title = { Text(if (hasZip) "Ready to run?" else "Run a script") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasZip) Text("${s.zipName}: ${s.files} files will be unzipped into", fontSize = 14.sp)
                Text(s.folder, color = Brand.AccentBlue, fontSize = 13.sp)
                if (hasZip) Hint("${s.overwrite} replace existing files, ${s.new} are new. All your other files stay. You can roll back afterwards.")
                Text("Then run this script", fontWeight = FontWeight.SemiBold)
                s.scripts.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = e.path }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen == e.path, onClick = { chosen = e.path })
                        Column {
                            Text(e.path, fontSize = 14.sp)
                            Hint(if (e.fromZip) "from the zip" else "already in the folder")
                        }
                    }
                }
                if (hasZip) {
                    Row(Modifier.fillMaxWidth().clickable { chosen = "" }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen == "", onClick = { chosen = "" })
                        Text("None, only unzip", fontSize = 14.sp)
                    }
                }
                Hint("Scripts can do anything on your laptop. Only run zips you trust.")
            }
        },
        confirmButton = { TextButton(onClick = { vm.runSheet(chosen) }, enabled = chosen.isNotEmpty() || hasZip) { Text("Run") } },
        dismissButton = { TextButton(onClick = { vm.cancelSheet() }) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ Jobs tab
fun formatTime(created: Double): String = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date((created * 1000).toLong()))

@Composable
fun JobsTab(vm: RemotyVm) {
    LaunchedEffect(Unit) { vm.refreshJobs() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Jobs", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.refreshJobs() }) { Text("↻ Refresh") }
        }
        if (vm.jobs.isEmpty()) Hint("Nothing yet. Start one from the Build tab.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.jobs, key = { it.id }) { j ->
                Panel(Modifier.clickable { vm.openJob(j.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(j.title, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.weight(1f))
                        StatusPill(j.status)
                    }
                    Hint(j.folder)
                    Hint(formatTime(j.created) + (if (j.phase.isNotBlank()) "  •  ${j.phase}" else ""))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Laptop tab
@Composable
fun LaptopTab(vm: RemotyVm, canLock: Boolean) {
    val zt by ZtManager.state.collectAsState()
    val p = vm.active
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Laptop", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (vm.online) Brand.StatusGreen else Brand.StatusRed)
                Text(vm.info?.name ?: p?.name ?: "", fontWeight = FontWeight.Bold)
            }
            Hint("Address ${p?.ip}:${p?.port}   •   agent ${vm.info?.version ?: "?"}")
            Hint(if (vm.online) "Reachable, ${vm.latency} ms" else "Not reachable right now")
        }
        Panel {
            Text("This phone on ZeroTier", fontWeight = FontWeight.SemiBold)
            Hint("Node ID ${zt.nodeId}")
            Hint("Network ${p?.networkId}   •   phone address ${zt.ip}")
            Hint("ZeroTier runs inside this app. No other VPN is used.")
        }
        vm.info?.let { info ->
            Panel {
                Text("Tools found on the laptop", fontWeight = FontWeight.SemiBold)
                info.tools.forEach { (k, v) -> Text((if (v) "✓ " else "✗ ") + k, color = if (v) Brand.StatusGreen else Brand.StatusRed) }
            }
            Panel {
                Text("Folders the phone may use", fontWeight = FontWeight.SemiBold)
                info.roots.forEach { Hint(it) }
                Hint("Add more in config.json on the laptop (\"roots\").")
            }
        }
        Panel {
            Text("Tools", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { vm.overlay = "remote" }, modifier = Modifier.fillMaxWidth()) { Text("🖥  Remote screen (touch + keyboard)") }
            if (vm.info?.remoteControl != true) Hint("Remote control is off: tick 'Allow remote control' in the Remoty Agent window on the laptop to click and type. Viewing the screen works either way.")
            OutlinedButton(onClick = { vm.overlay = "audit" }, modifier = Modifier.fillMaxWidth()) { Text("📜  Activity log (what the phone did)") }
            OutlinedButton(onClick = { vm.overlay = "cloud" }, modifier = Modifier.fillMaxWidth()) { Text("☁  Build on GitHub (laptop off)") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.copyDiagnostics() }) { Text("🩺  Copy diagnostics") }
                OutlinedButton(onClick = { vm.copyDiagnostics(share = true) }) { Text("Share") }
            }
            Hint("Diagnostics: versions, connection state, recent app log and any crash. The token is never included.")
            if (vm.info?.keepAwake == true) Hint("The laptop is kept awake while a job runs.")
        }
        Panel {
            Text("Theme", fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Palettes.all.forEach { pal ->
                    FilterChip(
                        selected = vm.themeKey == pal.key, onClick = { vm.applyTheme(pal.key) },
                        label = { Text(pal.name) }, leadingIcon = { Dot(pal.accent) },
                    )
                }
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lock the app", fontWeight = FontWeight.SemiBold)
                    Hint(if (canLock) "Ask for fingerprint, face or phone PIN every time you open Remoty." else "Set a screen lock on the phone first.")
                }
                Switch(vm.appLock, { vm.updateAppLock(it) }, enabled = canLock)
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Install the APK automatically", fontWeight = FontWeight.SemiBold)
                    Hint("When a build finishes, send the APK to this phone and open the installer. If the app is in the background you get a notification first.")
                }
                Switch(vm.autoInstall, { vm.updateAutoInstall(it) })
            }
        }
        Panel {
            Text("App updates", fontWeight = FontWeight.SemiBold)
            Hint("Version ${BuildConfig.VERSION_LABEL}")
            Hint("Updates come from the APK that Rebuild-APK.bat built on your laptop.")
            if (vm.updateStatus.isNotBlank()) Text(vm.updateStatus, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.checkUpdate() }) { Text("Check for updates") }
                if (vm.updateInfo != null) OutlinedButton(onClick = { vm.installUpdate() }) { Text("Install") }
            }
        }
        Spacer(Modifier.height(4.dp))
        GradientButton("Disconnect", Brand.DangerBrush) { vm.disconnect() }
    }
}
