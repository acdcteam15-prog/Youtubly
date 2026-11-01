package com.youtubly.app.presentation.blur

import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.youtubly.app.blur.ChannelRef
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(onBack: () -> Unit, vm: WhitelistViewModel = hiltViewModel()) {
    val excluded by vm.excluded.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val total by vm.totalExcluded.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }

    fun removeWithUndo(ch: ChannelRef) {
        vm.remove(ch)
        scope.launch {
            if (snack.showSnackbar("${ch.name} will be blurred again", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.add(ch)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Excluded channels"); Text("$total never blurred", style = MaterialTheme.typography.labelMedium) } },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { if (total > 0) IconButton({ confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "Clear all") } },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + com.youtubly.app.presentation.components.miniBarClearance())) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { vm.query.value = it },
                    placeholder = { Text("Search channels") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                Text("Videos from these channels skip face detection entirely — faster and lighter on battery.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }

            item { Header("Excluded") }
            if (excluded.isEmpty()) item {
                Text(if (query.isBlank()) "None yet. Long-press any video → \"Don't blur this channel\", or add from your subscriptions below."
                     else "No match", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
            items(excluded, key = { "x_" + it.id }) { ch ->
                val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                    if (v != SwipeToDismissBoxValue.Settled) removeWithUndo(ch); v != SwipeToDismissBoxValue.Settled
                })
                SwipeToDismissBox(state, backgroundContent = {
                    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                        Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                    }
                }, modifier = Modifier.animateItem()) {
                    ChannelRow(ch) { IconButton({ removeWithUndo(ch) }) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove") } }
                }
            }

            if (suggestions.isNotEmpty()) {
                item { Header("From your subscriptions") }
                items(suggestions, key = { "s_" + it.id }) { ch ->
                    ChannelRow(ch, Modifier.animateItem()) { IconButton({ vm.add(ch) }) { Icon(Icons.Rounded.AddCircleOutline, "Exclude") } }
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Blur all channels again?") },
        text = { Text("This removes all $total excluded channels.") },
        confirmButton = {
            TextButton({
                confirmClear = false; val backup = excluded; vm.clearAll()
                scope.launch { if (snack.showSnackbar("Cleared", "Undo") == SnackbarResult.ActionPerformed) vm.restore(backup) }
            }) { Text("Clear") }
        },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable private fun Header(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))

@Composable
private fun ChannelRow(ch: ChannelRef, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    ListItem(
        modifier = modifier,
        leadingContent = {
            // Plain AsyncImage is fine: these channels are the ones the user chose not to blur
            if (ch.avatarUrl != null) AsyncImage(ch.avatarUrl, null, Modifier.size(40.dp).clip(CircleShape))
            else Box(Modifier.size(40.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) { Text(ch.name.take(1).uppercase()) }
                }
            }
        },
        headlineContent = { Text(ch.name, maxLines = 1) },
        trailingContent = trailing,
    )
}
