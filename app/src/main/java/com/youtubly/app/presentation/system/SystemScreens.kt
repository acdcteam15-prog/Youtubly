package com.youtubly.app.presentation.system

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.youtubly.app.BuildConfig
import com.youtubly.app.core.ErrorEntry
import com.youtubly.app.core.ErrorLog
import com.youtubly.app.data.local.datastore.SponsorCategories
import com.youtubly.app.data.local.datastore.UserPreferences
import com.youtubly.app.data.system.SettingsBackup
import com.youtubly.app.data.system.StorageItem
import com.youtubly.app.data.system.StorageManager
import com.youtubly.app.presentation.components.EmptyState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ---------------------------------------------------------------- shared scaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemScaffold(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
                           snackbar: SnackbarHostState? = null, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = actions,
            )
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        content = content,
    )
}

// ---------------------------------------------------------------- Diagnostics

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val entries by ErrorLog.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    val header = "Youtubly ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · NewPipe ${BuildConfig.NEWPIPE_VERSION} · " +
        "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

    SystemScaffold("Diagnostics", onBack, snackbar = snack, actions = {
        IconButton({ clipboard.setText(AnnotatedString(ErrorLog.report(header))); scope.launch { snack.showSnackbar("Report copied") } },
            enabled = entries.isNotEmpty()) { Icon(Icons.Rounded.ContentCopy, "Copy report") }
        IconButton({ confirmClear = true }, enabled = entries.isNotEmpty()) { Icon(Icons.Rounded.DeleteSweep, "Clear") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(header, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (entries.isEmpty()) {
                EmptyState(Icons.Rounded.CheckCircle, "No errors recorded", "Failures and crashes show up here, with the time and details.")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
                    items(entries, key = { it.timeMs.toString() + it.trace.hashCode() }) { ErrorRow(it) }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear the error log?") },
        confirmButton = { TextButton({ ErrorLog.clear(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ErrorRow(e: ErrorEntry) {
    var expanded by remember { mutableStateOf(false) }
    val time = remember(e.timeMs) { SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault()).format(Date(e.timeMs)) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.animateContentSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (e.area == "Crash") Icons.Rounded.ErrorOutline else Icons.Rounded.WarningAmber, null,
                tint = if (e.area == "Crash") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("${e.area} · ${e.type}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (e.message.isNotBlank()) Text(e.message, style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) 20 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        if (expanded) Text(e.trace, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
    HorizontalDivider()
}

// ---------------------------------------------------------------- Storage

@HiltViewModel
class StorageViewModel @Inject constructor(private val storage: StorageManager) : ViewModel() {
    private val _items = MutableStateFlow<List<StorageItem>?>(null)
    val items: StateFlow<List<StorageItem>?> = _items
    var busy by mutableStateOf<String?>(null)
        private set

    init { refresh() }
    fun refresh() = viewModelScope.launch { _items.value = storage.scan() }
    fun clear(id: String) = viewModelScope.launch {
        busy = id
        runCatching { storage.clear(id) }.onFailure { ErrorLog.record("Storage", it) }
        _items.value = storage.scan(); busy = null
    }
}

@Composable
fun StorageScreen(onBack: () -> Unit, vm: StorageViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    SystemScaffold("Storage", onBack) { pad ->
        val list = items
        if (list == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@SystemScaffold
        }
        val total = list.sumOf { it.bytes }
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + com.youtubly.app.presentation.components.miniBarClearance()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Youtubly uses ${size(ctx, total)}", style = MaterialTheme.typography.titleMedium)
                Text("Caches refill as you use the app. Downloads and the model are managed in their own screens.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(list, key = { it.id }) { item ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge)
                            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (total > 0) LinearProgressIndicator(
                                progress = { (item.bytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(size(ctx, item.bytes), style = MaterialTheme.typography.labelLarge)
                            if (item.clearable) {
                                if (vm.busy == item.id) CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
                                else TextButton({ vm.clear(item.id) }, enabled = item.bytes > 0 && vm.busy == null) { Text("Clear") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun size(ctx: Context, bytes: Long) = Formatter.formatShortFileSize(ctx, bytes)

// ---------------------------------------------------------------- Backup & restore

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: SettingsBackup,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    var working by mutableStateOf(false)
        private set

    fun exportTo(uri: android.net.Uri) = launchTask("Settings saved") {
        val text = backup.export()
        withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(text) } }
    }

    fun importFrom(uri: android.net.Uri) = launchTask(null) {
        val text = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText().also { t -> require(t.length < 2_000_000) { "File is too large" } } }
        }
        val n = backup.import(text)
        messages.emit("Restored $n settings")
    }

    private fun launchTask(done: String?, block: suspend () -> Unit) = viewModelScope.launch {
        working = true
        runCatching { block() }
            .onSuccess { done?.let { messages.emit(it) } }
            .onFailure { ErrorLog.record("Backup", it); messages.emit(it.message ?: "Something went wrong") }
        working = false
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    val date = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportTo) }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importFrom) }
    var confirmImport by remember { mutableStateOf(false) }

    SystemScaffold("Backup & restore", onBack, snackbar = snack) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Android backup is off for Youtubly, so reinstalling wipes your settings. Save them to a file to keep them.",
                style = MaterialTheme.typography.bodyMedium)
            Text("Included: playback, appearance, SponsorBlock, bedtime, content blur and music remover settings, plus excluded channels and videos.\n" +
                "Not included: sign-in, downloads, history, playlists, and the gender model.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ create.launch("youtubly-settings-$date.json") }, Modifier.fillMaxWidth(), enabled = !vm.working) {
                Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(8.dp)); Text("Save settings to a file")
            }
            OutlinedButton({ confirmImport = true }, Modifier.fillMaxWidth(), enabled = !vm.working) {
                Icon(Icons.Rounded.Restore, null); Spacer(Modifier.width(8.dp)); Text("Restore from a file")
            }
            if (vm.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (confirmImport) AlertDialog(
        onDismissRequest = { confirmImport = false },
        title = { Text("Restore settings?") },
        text = { Text("Settings in the file replace the current ones. Settings the file doesn't contain stay as they are.") },
        confirmButton = { TextButton({ confirmImport = false; pickBackup.launch(arrayOf("application/json", "*/*")) }) { Text("Choose file") } },
        dismissButton = { TextButton({ confirmImport = false }) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- SponsorBlock categories

@HiltViewModel
class SponsorBlockViewModel @Inject constructor(private val prefs: UserPreferences) : ViewModel() {
    val enabled = prefs.autoSkipSponsors.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val categories = prefs.sponsorSkipCategories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SponsorCategories.DEFAULT_SKIP)
    fun toggle(id: String) = viewModelScope.launch {
        val cur = categories.value
        prefs.setSponsorSkipCategories(if (id in cur) cur - id else cur + id)
    }
    fun reset() = viewModelScope.launch { prefs.setSponsorSkipCategories(SponsorCategories.DEFAULT_SKIP) }
}

@Composable
fun SponsorBlockScreen(onBack: () -> Unit, vm: SponsorBlockViewModel = hiltViewModel()) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    SystemScaffold("SponsorBlock", onBack, actions = { TextButton(vm::reset) { Text("Reset") } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
            item {
                Text(
                    if (enabled) "Choose which community-marked parts are skipped automatically."
                    else "Auto-skip is off (You → Settings). These choices apply when you turn it on.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
                )
            }
            items(SponsorCategories.ALL, key = { it.first }) { (id, label) ->
                Row(Modifier.fillMaxWidth().clickable { vm.toggle(id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Checkbox(id in cats, { vm.toggle(id) })
                }
            }
        }
    }
}
