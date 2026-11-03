package com.example.remoty.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.remoty.JobSummary
import com.example.remoty.RemotyVm
import kotlinx.coroutines.delay

// ------------------------------------------------------------------ folder / file browser
@Composable
fun FileBrowser(vm: RemotyVm, pick: Boolean) {
    LaunchedEffect(Unit) { if (vm.browse == null) vm.browseTo("") }
    var newDialog by remember { mutableStateOf(false) }
    val b = vm.browse
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val up = b?.parent
                if (up != null) vm.browseTo(up) else if (pick) vm.picking = false
            }) { Text("←") }
            Text(
                if (pick) "Choose a folder" else "Files", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (b != null && b.path.isNotEmpty()) {
                TextButton(onClick = { vm.toggleFavorite(b.path) }) { Text(if (b.path in vm.favorites) "⭐ Saved" else "☆ Save") }
            }
        }
        Hint(if (b == null || b.path.isEmpty()) "Allowed folders on the laptop" else b.path)
        vm.browseError?.let { Text(it, color = Brand.StatusRed, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        if (b != null && b.path.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pick) Button(onClick = { vm.chooseFolder(b.path) }) { Text("Use this folder") }
                OutlinedButton(onClick = { newDialog = true }) { Text("+ New folder") }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (vm.browseLoading) Hint("Loading...")
        LazyColumn(Modifier.weight(1f)) {
            val list = b?.entries?.filter { !pick || it.dir } ?: emptyList()
            items(list, key = { it.path }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (e.dir) vm.browseTo(e.path)
                        else if (e.name.endsWith(".apk", ignoreCase = true)) vm.installApkFile(e.path, e.name)
                        else vm.openFile(e.path)
                    }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (e.dir) "📁" else if (e.name.endsWith(".apk", ignoreCase = true)) "📲" else "📄", fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 1)
                        if (!e.dir) Hint("${e.size / 1024} KB")
                    }
                    if (e.dir && pick) TextButton(onClick = { vm.chooseFolder(e.path) }) { Text("Use") }
                }
            }
        }
    }
    if (newDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDialog = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Folder name") }) },
            confirmButton = { TextButton(onClick = { vm.newFolder(name); newDialog = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newDialog = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ editor
@Composable
fun EditorScreen(vm: RemotyVm) {
    val path = vm.editorPath ?: return
    val dirty = vm.editorText != vm.editorOriginal
    var warned by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (dirty && !warned) { warned = true; vm.toast = "Unsaved changes. Tap Save, or press ← again to discard." }
                else vm.closeEditor()
            }) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(path.substringAfterLast('\\').substringAfterLast('/'), fontWeight = FontWeight.Bold, maxLines = 1)
                Hint(if (dirty) "unsaved changes" else path)
            }
            Button(onClick = { vm.saveFile() }, enabled = dirty) { Text("Save") }
        }
        if (vm.editorLoading) Hint("Loading...")
        OutlinedTextField(
            vm.editorText, { vm.editorText = it; warned = false },
            Modifier.fillMaxSize().padding(top = 8.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        )
    }
}

// ------------------------------------------------------------------ job screen
private fun lineColor(kind: String): Color = when (kind) {
    "err" -> Brand.StatusRed
    "prompt" -> Brand.StatusAmber
    "input" -> Brand.AccentBlue
    "sys" -> Brand.TextSecondary
    else -> Brand.TextPrimary
}

@Composable
fun JobScreen(vm: RemotyVm) {
    val j = vm.jobDetail
    var sub by remember { mutableStateOf(0) }
    var confirmRollback by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Brand.Header).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.closeJob() }) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(j?.title ?: "Job", fontWeight = FontWeight.Bold, maxLines = 1)
                Hint(j?.phase ?: "")
            }
            if (j != null) StatusPill(j.status)
        }
        if (j != null) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (j.active) Button(onClick = { vm.stopJob(j.id) }) { Text("■ Stop") }
                if (j.hasArtifact) Button(onClick = { vm.installApk(j) }) { Text("📲 Install APK") }
                OutlinedButton(onClick = { vm.copyReport(j.id) }) { Text("📋 Copy for Claude") }
                OutlinedButton(onClick = { vm.shareReport(j.id) }) { Text("↗ Share") }
                if (!j.active) OutlinedButton(onClick = { vm.rerun(j) }) { Text("↻ Run again") }
                if (j.canRollback && !j.active) OutlinedButton(onClick = { confirmRollback = true }) { Text("⏪ Roll back") }
            }
            if (vm.problems.isNotEmpty() && !j.active) {
                Panel(Modifier.padding(horizontal = 12.dp)) {
                    Text("What went wrong", fontWeight = FontWeight.Bold, color = Brand.StatusRed)
                    vm.problems.take(4).forEach { p ->
                        Text(p.title, fontSize = 13.sp)
                        if (p.file.isNotEmpty()) Hint("${p.file}:${p.line}")
                    }
                    if (vm.problems.size > 4) Hint("+${vm.problems.size - 4} more: Copy for Claude includes all of them")
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sub == 0, onClick = { sub = 0 }, label = { Text("Log") })
                FilterChip(selected = sub == 1, onClick = { sub = 1 }, label = { Text("Screen") })
                FilterChip(selected = sub == 2, onClick = { sub = 2 }, label = { Text("Preview") })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            when (sub) {
                0 -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { LogView(vm) }
                    if (j != null && j.active) InputBar(vm, j)
                }
                1 -> ScreenView(vm)
                else -> PreviewView(vm)
            }
        }
    }
    if (confirmRollback && j != null) {
        AlertDialog(
            onDismissRequest = { confirmRollback = false },
            title = { Text("Roll back?") },
            text = { Text("This puts the folder back exactly as it was before this job started. Files Claude created or changed since then are removed.") },
            confirmButton = { TextButton(onClick = { vm.rollbackJob(j.id); confirmRollback = false }) { Text("Roll back", color = Brand.StatusRed) } },
            dismissButton = { TextButton(onClick = { confirmRollback = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LogView(vm: RemotyVm) {
    val state = rememberLazyListState()
    var prev by remember { mutableStateOf(0) }
    LaunchedEffect(vm.logLines.size) {
        val n = vm.logLines.size
        val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (n > 0 && (prev == 0 || last >= prev - 2)) state.scrollToItem(n - 1)
        prev = n
    }
    if (vm.logLines.isEmpty()) Hint("Waiting for output...")
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(vm.logLines.size) { i ->
                val l = vm.logLines[i]
                val prefix = when (l.kind) { "prompt" -> "⌨ "; "input" -> "↪ "; "err" -> "⚠ "; else -> "" }
                Text(prefix + l.text, color = lineColor(l.kind), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun ScreenView(vm: RemotyVm) {
    var auto by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshShot() }
    LaunchedEffect(auto) { while (auto) { delay(2000); vm.refreshShot() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.refreshShot() }) { Text(if (vm.shotLoading) "..." else "↻ Refresh") }
            Text("Auto", fontSize = 13.sp)
            Switch(auto, { auto = it })
        }
        val s = vm.shot
        if (s == null) Hint("This shows the laptop's screen, so a desktop app that opened its own window is visible here. The laptop must be signed in and unlocked.")
        else Image(s, "Laptop screen", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
}

@Composable
private fun PreviewView(vm: RemotyVm) {
    val port = vm.jobDetail?.previewPort
    val url = if (port != null) vm.previewUrl(port) else null
    if (url == null) {
        Hint("No web preview for this job: the app did not print a localhost web address. Use the Screen tab for desktop windows.")
        return
    }
    AndroidView(
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        update = { w -> if (w.url == null) w.loadUrl(url) },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun InputBar(vm: RemotyVm, j: JobSummary) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (j.waitingInput) Text("⌨ The script is waiting for your answer", color = Brand.StatusAmber, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Type an answer") })
            Button(onClick = { vm.sendInput(j.id, text); text = "" }) { Text("Send") }
            OutlinedButton(onClick = { vm.sendInput(j.id, "") }) { Text("↵") }
        }
    }
}
