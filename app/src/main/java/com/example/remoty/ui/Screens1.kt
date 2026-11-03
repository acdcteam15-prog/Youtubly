package com.example.remoty.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.remoty.Profile
import com.example.remoty.R
import com.example.remoty.RemotyVm
import com.example.remoty.Stage
import com.example.remoty.ZtManager
import com.example.remoty.ZtPhase

@Composable
fun RemotyRoot(vm: RemotyVm, canLock: Boolean, onUnlock: () -> Unit, onPickZip: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.toast) {
        val t = vm.toast
        if (t != null) { snack.showSnackbar(t); vm.toast = null }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            vm.locked -> LockScreen(onUnlock)
            vm.overlay == "cloud" -> CloudScreen(vm, onPickZip)
            vm.active == null -> ProfilesScreen(vm)
            vm.stage != Stage.Ready -> ConnectingScreen(vm)
            else -> MainScreen(vm, canLock, onPickZip)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(14.dp))
        Text("Remoty is locked", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        GradientButton("Unlock", Brand.UsageBrush, onClick = onUnlock)
    }
}

// ------------------------------------------------------------------ laptops (profiles)
@Composable
fun ProfilesScreen(vm: RemotyVm) {
    var editing by remember { mutableStateOf<Profile?>(null) }
    var showForm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(60.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Remoty", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Hint("Build apps on your laptop from your phone")
            }
        }
        Spacer(Modifier.height(16.dp))
        if (vm.profiles.isEmpty()) {
            Panel {
                Text("Add your laptop", fontWeight = FontWeight.Bold)
                Hint("You need: your ZeroTier Network ID (16 characters), the laptop's ZeroTier address, and the pairing token shown in the Remoty Agent window on the laptop.")
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.profiles, key = { it.id }) { p ->
                Panel(Modifier.clickable { vm.connect(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Hint("${p.ip}:${p.port}")
                            Hint("network ${p.networkId}")
                        }
                        TextButton(onClick = { editing = p; showForm = true }) { Text("Edit") }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        GradientButton("+ Add a laptop", Brand.UsageBrush) { editing = null; showForm = true }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.overlay = "cloud" }, modifier = Modifier.fillMaxWidth()) { Text("☁  Build on GitHub (laptop off)") }
    }
    if (showForm) ProfileDialog(vm, editing) { showForm = false }
}

@Composable
private fun ProfileDialog(vm: RemotyVm, existing: Profile?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "My laptop") }
    var network by remember { mutableStateOf(existing?.networkId ?: "") }
    var ip by remember { mutableStateOf(existing?.ip ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 8765).toString()) }
    var token by remember { mutableStateOf(existing?.token ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a laptop" else "Edit laptop") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    network, { network = it.trim() }, label = { Text("ZeroTier Network ID (16 characters)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                OutlinedTextField(ip, { ip = it.trim() }, label = { Text("Laptop's ZeroTier address (e.g. 10.147.17.5)") }, singleLine = true)
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Agent port") }, singleLine = true)
                OutlinedTextField(
                    token, { token = it.trim() }, label = { Text("Pairing token (XXXX-XXXX-XXXX)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                error?.let { Text(it, color = Brand.StatusRed, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    !Regex("^[0-9a-fA-F]{16}$").matches(network) -> "The Network ID is 16 letters/digits (0-9, a-f)."
                    !Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip) -> "Enter the laptop's ZeroTier IPv4 address."
                    (port.toIntOrNull() ?: 0) !in 1..65535 -> "Port must be 1-65535."
                    token.length < 8 -> "Enter the pairing token from the laptop."
                    name.isBlank() -> "Give it a name."
                    else -> null
                }
                if (error == null) {
                    vm.saveProfile(Profile(existing?.id ?: vm.newId(), name.trim(), network.lowercase(), ip, port.toInt(), token))
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { vm.deleteProfile(existing); onDismiss() }) { Text("Delete", color = Brand.StatusRed) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ------------------------------------------------------------------ connecting
@Composable
fun ConnectingScreen(vm: RemotyVm) {
    val zt by ZtManager.state.collectAsState()
    val p = vm.active ?: return
    val ctx = LocalContext.current
    BackHandler { vm.disconnect() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connecting to ${p.name}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Panel {
            val ztStarted = zt.nodeId.isNotEmpty()
            val joined = zt.phase == ZtPhase.Ready
            val agentOk = vm.stage == Stage.Ready
            Step(if (ztStarted) "done" else if (vm.connectError == null) "busy" else "fail", "ZeroTier node started inside the app")
            Step(
                if (joined) "done" else if (!ztStarted) "wait" else if (vm.connectError == null) "busy" else "fail",
                if (joined) "Joined the network (phone address ${zt.ip})" else "Joined network ${p.networkId}",
            )
            Step(if (agentOk) "done" else if (!joined) "wait" else if (vm.connectError == null) "busy" else "fail", "Reached the laptop agent at ${p.ip}:${p.port}")
        }
        if (zt.phase == ZtPhase.NeedsAuth) {
            Panel {
                Text("One-time step: authorise this phone", fontWeight = FontWeight.Bold, color = Brand.StatusAmber)
                Hint("The phone joined as a new device. On your laptop or any browser: my.zerotier.com > your network > Members, then tick Auth next to this ID:")
                Text(zt.nodeId, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                OutlinedButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("ZeroTier node ID", zt.nodeId))
                    vm.toast = "Node ID copied"
                }) { Text("Copy ID") }
                Hint("This screen continues by itself as soon as you tick it.")
            }
        } else if (vm.connectError == null) {
            Hint(if (zt.message.isNotEmpty()) zt.message else vm.agentStep)
        }
        vm.connectError?.let { err ->
            Panel {
                Text("Could not connect", fontWeight = FontWeight.Bold, color = Brand.StatusRed)
                Text(err, fontSize = 14.sp)
            }
            GradientButton("Try again", Brand.UsageBrush) { vm.connect(p) }
        }
        OutlinedButton(onClick = { vm.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun Step(state: String, label: String) {
    val (sym, color) = when (state) {
        "done" -> "✓" to Brand.StatusGreen
        "busy" -> "…" to Brand.StatusAmber
        "fail" -> "✗" to Brand.StatusRed
        else -> "○" to Brand.TextSecondary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(sym, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
        Text(label, modifier = Modifier.alpha(if (state == "wait") 0.5f else 1f))
    }
}
