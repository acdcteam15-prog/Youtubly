package com.example.remoty.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remoty.CloudProject
import com.example.remoty.RemotyVm
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

// ------------------------------------------------------------------ remote screen (see and touch the laptop)
@Composable
fun RemoteScreen(vm: RemotyVm) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var mode by remember { mutableStateOf("click") }          // click, drag, scroll
    var live by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var text by remember { mutableStateOf("") }
    BackHandler { vm.overlay = null }

    LaunchedEffect(live) {
        while (live) {
            try { frame = vm.fetchScreen(1100, 60); error = null } catch (e: Exception) { error = e.message ?: "no picture" }
            delay(900)
        }
    }

    fun send(action: String, x: Float = 0.5f, y: Float = 0.5f, dy: Float = 0f) {
        vm.remoteSend(JSONObject().put("action", action).put("x", x.toDouble()).put("y", y.toDouble()).put("dy", dy.toDouble()))
    }

    fun key(k: String, vararg mods: String) {
        vm.remoteSend(JSONObject().put("action", "key").put("key", k).put("mods", JSONArray(mods.toList())))
    }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.overlay = null }) { Text("←") }
            Text("Remote screen", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("Live", fontSize = 13.sp)
            Switch(live, { live = it })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "click", onClick = { mode = "click" }, label = { Text("Tap = click") })
            FilterChip(selected = mode == "drag", onClick = { mode = "drag" }, label = { Text("Drag = mouse drag") })
            FilterChip(selected = mode == "scroll", onClick = { mode = "scroll" }, label = { Text("Drag = scroll") })
        }
        val msg = vm.remoteError ?: error
        if (msg != null) Text(msg, color = Brand.StatusAmber, fontSize = 13.sp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val f = frame
            if (f == null) Hint("Waiting for the laptop's screen... (the laptop must be signed in and unlocked)")
            else {
                var lastMove = 0L
                var acc = 0f
                Image(
                    f, "Laptop screen",
                    Modifier.fillMaxWidth().onSizeChanged { size = it }.pointerInput(mode, size) {
                        fun nx(v: Float) = (v / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        fun ny(v: Float) = (v / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                        when (mode) {
                            "click" -> detectTapGestures(
                                onTap = { send("click", nx(it.x), ny(it.y)) },
                                onDoubleTap = { send("dblclick", nx(it.x), ny(it.y)) },
                                onLongPress = { send("rclick", nx(it.x), ny(it.y)) },
                            )
                            "drag" -> {
                                var lx = 0.5f; var ly = 0.5f
                                detectDragGestures(
                                    onDragStart = { lx = nx(it.x); ly = ny(it.y); send("down", lx, ly) },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        lx = nx(change.position.x); ly = ny(change.position.y)
                                        val now = System.nanoTime()
                                        if (now - lastMove > 70_000_000L) { lastMove = now; send("move", lx, ly) }
                                    },
                                    onDragEnd = { send("up", lx, ly) },
                                    onDragCancel = { send("up", lx, ly) },
                                )
                            }
                            else -> detectDragGestures(onDrag = { change, drag ->
                                change.consume()
                                acc += drag.y
                                if (kotlin.math.abs(acc) > 40f) {
                                    send("scroll", nx(change.position.x), ny(change.position.y), if (acc > 0) -3f else 3f)
                                    acc = 0f
                                }
                            })
                        }
                    },
                    contentScale = ContentScale.FillWidth,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Type on the laptop") })
                Button(onClick = { vm.remoteSend(JSONObject().put("action", "text").put("text", text)); text = "" }) { Text("Type") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { key("enter") }) { Text("Enter") }
                OutlinedButton(onClick = { key("backspace") }) { Text("⌫") }
                OutlinedButton(onClick = { key("esc") }) { Text("Esc") }
                OutlinedButton(onClick = { key("tab") }) { Text("Tab") }
                OutlinedButton(onClick = { key("left") }) { Text("←") }
                OutlinedButton(onClick = { key("up") }) { Text("↑") }
                OutlinedButton(onClick = { key("down") }) { Text("↓") }
                OutlinedButton(onClick = { key("right") }) { Text("→") }
                OutlinedButton(onClick = { key("win") }) { Text("Win") }
                OutlinedButton(onClick = { key("tab", "alt") }) { Text("Alt+Tab") }
                OutlinedButton(onClick = { key("c", "ctrl") }) { Text("Ctrl+C") }
                OutlinedButton(onClick = { key("v", "ctrl") }) { Text("Ctrl+V") }
                OutlinedButton(onClick = { key("a", "ctrl") }) { Text("Ctrl+A") }
                OutlinedButton(onClick = { key("z", "ctrl") }) { Text("Ctrl+Z") }
            }
        }
    }
}

// ------------------------------------------------------------------ activity log
@Composable
fun AuditScreen(vm: RemotyVm) {
    LaunchedEffect(Unit) { vm.loadAudit() }
    BackHandler { vm.overlay = null }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.overlay = null }) { Text("←") }
            Text("Activity log", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.loadAudit() }) { Text(if (vm.auditLoading) "..." else "↻") }
        }
        Hint("Everything this laptop's agent did for a phone, newest first. Also saved in data\\audit.log next to the agent.")
        if (vm.audit.isEmpty() && !vm.auditLoading) Hint("Nothing recorded yet.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.audit.size) { i ->
                val e = vm.audit[i]
                val warn = e.action == "DENIED"
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.action, fontWeight = FontWeight.Bold, color = if (warn) Brand.StatusRed else Brand.AccentBlue, modifier = Modifier.weight(1f))
                        Text(e.ts.substringAfter(' '), fontSize = 12.sp, color = Brand.TextSecondary)
                    }
                    if (e.detail.isNotBlank()) Text(e.detail, fontSize = 13.sp)
                    Hint("${e.ts.substringBefore(' ')}   from ${e.ip}")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ build on GitHub (laptop off)
@Composable
fun CloudScreen(vm: RemotyVm, onPickZip: () -> Unit) {
    val ctx = LocalContext.current
    var tokenText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }        // index of the project being edited, -1 = new
    val busy = vm.cloudPhase in setOf("uploading", "queued", "building", "downloading")
    val selected = vm.cloudProjects.getOrNull(vm.cloudSelected)
    BackHandler { vm.overlay = null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.overlay = null }) { Text("←") }
            Text("Build on GitHub", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Hint("No laptop needed: the phone sends your zip to your GitHub repository, GitHub builds it, and the APK comes back to this phone.")

        Panel {
            Text("1. GitHub token", fontWeight = FontWeight.SemiBold)
            if (vm.cloudToken.isNotBlank()) {
                Text("Saved on this phone (encrypted) ✓", color = Brand.StatusGreen)
                TextButton(onClick = { vm.saveCloudToken("") }) { Text("Remove token") }
            } else {
                Hint("On github.com: Settings > Developer settings > Personal access tokens > Fine-grained tokens. Repository access: only your repository. Permissions: Contents = Read and write, Actions = Read and write.")
                OutlinedTextField(tokenText, { tokenText = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Paste the token") },
                    visualTransformation = PasswordVisualTransformation())
                Button(onClick = { vm.saveCloudToken(tokenText); tokenText = "" }, enabled = tokenText.isNotBlank()) { Text("Save token") }
            }
        }

        Panel {
            Text("2. Project", fontWeight = FontWeight.SemiBold)
            if (vm.cloudProjects.isEmpty()) Hint("Add the repository you want to build.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.cloudProjects.forEachIndexed { i, p ->
                    FilterChip(selected = vm.cloudSelected == i, onClick = { vm.cloudSelected = i }, label = { Text(p.name) })
                }
            }
            if (selected != null) Hint("${selected.repo}   (${selected.workflow} on ${selected.branch})")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = -1 }) { Text("+ Add project") }
                if (selected != null) TextButton(onClick = { editing = vm.cloudSelected }) { Text("Edit") }
            }
        }

        Panel {
            Text("3. The zip", fontWeight = FontWeight.SemiBold)
            val z = vm.zip
            if (z == null) {
                Hint("Pick it from Downloads, or use Share, then Remoty.")
                Button(onClick = onPickZip, enabled = !vm.zipBusy && !busy) { Text("Pick a zip file") }
            } else {
                Text("📦 ${z.name}", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickZip, enabled = !busy) { Text("Pick another") }
                    TextButton(onClick = { vm.clearZip() }, enabled = !busy) { Text("Remove") }
                }
            }
        }

        GradientButton(
            "☁  Upload the zip and build", Brand.BuildBrush,
            enabled = !busy && vm.zip != null && selected != null && vm.cloudToken.isNotBlank(),
        ) { vm.startCloudBuild() }
        Hint("Files in the zip replace the ones in the repository; everything else stays.")

        if (vm.cloudPhase != "idle") {
            Panel {
                Text(vm.cloudStatus, fontWeight = FontWeight.SemiBold,
                    color = when (vm.cloudPhase) { "failed" -> Brand.StatusRed; "done" -> Brand.StatusGreen; else -> Brand.TextPrimary })
                if (vm.cloudPhase == "uploading") LinearProgressIndicator(progress = { vm.cloudProgress }, modifier = Modifier.fillMaxWidth())
                else if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                vm.cloudSteps.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Brand.TextSecondary) }
                if (vm.cloudRunUrl.isNotBlank()) {
                    TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.cloudRunUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open the build on GitHub") }
                }
                if (busy) OutlinedButton(onClick = { vm.cancelCloud() }) { Text("Cancel") }
                if (vm.cloudPhase == "done") Button(onClick = { vm.installCloudApk() }) { Text("📲 Install the APK") }
                if (vm.cloudPhase == "failed" && (vm.cloudProblems.isNotEmpty() || vm.cloudLog.isNotBlank())) {
                    Text("What went wrong", fontWeight = FontWeight.Bold, color = Brand.StatusRed)
                    vm.cloudProblems.take(5).forEach { p ->
                        Text(p.title, fontSize = 13.sp)
                        if (p.file.isNotEmpty()) Hint("${p.file}:${p.line}")
                    }
                    Button(onClick = { vm.copyCloudProblems() }) { Text("📋 Copy for Claude") }
                }
            }
        }
    }

    editing?.let { idx ->
        val existing = vm.cloudProjects.getOrNull(idx)
        var name by remember(idx) { mutableStateOf(existing?.name ?: "Remoty") }
        var repo by remember(idx) { mutableStateOf(existing?.repo ?: "acdcteam15-prog/Remoty") }
        var workflow by remember(idx) { mutableStateOf(existing?.workflow ?: "build.yml") }
        var branch by remember(idx) { mutableStateOf(existing?.branch ?: "main") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (existing == null) "Add project" else "Edit project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(repo, { repo = it.trim() }, label = { Text("Repository (owner/name)") }, singleLine = true)
                    OutlinedTextField(workflow, { workflow = it.trim() }, label = { Text("Workflow file") }, singleLine = true)
                    OutlinedTextField(branch, { branch = it.trim() }, label = { Text("Branch") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && Regex("^[\\w.-]+/[\\w.-]+$").matches(repo) && workflow.isNotBlank() && branch.isNotBlank(),
                    onClick = { vm.saveCloudProject(CloudProject(name.trim(), repo, workflow, branch), if (existing == null) null else idx); editing = null },
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (existing != null) TextButton(onClick = { vm.deleteCloudProject(idx); editing = null }) { Text("Delete", color = Brand.StatusRed) }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}
