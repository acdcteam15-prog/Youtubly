package com.youtubly.app.presentation.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.youtubly.app.data.repository.ChannelGroupsRepository
import com.youtubly.app.domain.library.GroupFeed
import com.youtubly.app.domain.model.Video
import com.youtubly.app.domain.repository.ChannelRepository
import com.youtubly.app.presentation.components.PlainBackground
import com.youtubly.app.presentation.components.SimpleTopBar
import com.youtubly.app.presentation.components.VideoCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

// ---------------------------------------------------------------- view models

@HiltViewModel
class ChannelGroupsViewModel @Inject constructor(private val repo: ChannelGroupsRepository) : ViewModel() {
    val groups: StateFlow<Map<String, List<String>>> =
        repo.groups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun toggle(group: String, channelUrl: String) { viewModelScope.launch { repo.toggle(group, channelUrl) } }
    fun create(name: String, channelUrl: String? = null) { viewModelScope.launch { repo.create(name, channelUrl) } }
    fun rename(old: String, new: String) { viewModelScope.launch { repo.rename(old, new) } }
    fun delete(name: String) { viewModelScope.launch { repo.delete(name) } }
    fun removeChannel(group: String, url: String) { viewModelScope.launch { repo.removeChannel(group, url) } }
}

data class GroupFeedUi(val loading: Boolean = true, val videos: List<Video> = emptyList(), val channels: Int = 0, val failed: Int = 0)

@HiltViewModel
class GroupFeedViewModel @Inject constructor(
    private val repo: ChannelGroupsRepository,
    private val channelRepository: ChannelRepository
) : ViewModel() {
    private val _ui = MutableStateFlow(GroupFeedUi())
    val ui: StateFlow<GroupFeedUi> = _ui
    private var loadedFor: String? = null

    /** Newest videos of every channel in the group, merged (at most 4 channels are fetched at once). */
    fun load(group: String, force: Boolean = false) {
        if (!force && loadedFor == group) return
        loadedFor = group
        _ui.value = GroupFeedUi(loading = true)
        viewModelScope.launch {
            val members = repo.groups.first()[group].orEmpty()
            val gate = Semaphore(4)
            val results = coroutineScope {
                members.map { url -> async { gate.withPermit { channelRepository.getChannel(url).getOrNull()?.videos?.take(12) } } }.awaitAll()
            }
            _ui.value = GroupFeedUi(
                loading = false,
                videos = GroupFeed.merge(results.filterNotNull()),
                channels = members.size,
                failed = results.count { it == null }
            )
        }
    }
}

// ---------------------------------------------------------------- channel page: which groups is this channel in?

/** Chips on a channel page: tap a group to add or remove the channel, or make a new group. */
@Composable
fun ChannelGroupChips(channelUrl: String, modifier: Modifier = Modifier, vm: ChannelGroupsViewModel = hiltViewModel()) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
        items(groups.keys.toList()) { g ->
            FilterChip(
                selected = channelUrl in groups[g].orEmpty(),
                onClick = { vm.toggle(g, channelUrl) },
                label = { Text(g) },
                leadingIcon = { Icon(Icons.Rounded.Folder, null) }
            )
        }
        item { AssistChip(onClick = { creating = true }, label = { Text("New group") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }) }
    }
    if (creating) NameDialog("New group", "", "Create", onConfirm = { vm.create(it, channelUrl); creating = false }, onDismiss = { creating = false })
}

@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------- screens

/** All your groups; tap one for its merged feed. */
@Composable
fun ChannelGroupsScreen(onBack: () -> Unit, onOpenGroup: (String) -> Unit, vm: ChannelGroupsViewModel = hiltViewModel()) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    PlainBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SimpleTopBar("Channel groups", onBack, actions = { IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "New group") } })
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Make groups like \"Lectures\" or \"News\" and see the newest videos of all their channels in one place. Add a channel to a group from its page.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(groups.keys.toList(), key = { it }) { g ->
                    var menu by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenGroup(g) }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(g, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text("${groups[g].orEmpty().size} channels", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; renaming = g })
                                DropdownMenuItem(text = { Text("Delete group") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; deleting = g })
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) NameDialog("New group", "", "Create", onConfirm = { vm.create(it); creating = false }, onDismiss = { creating = false })
    renaming?.let { old -> NameDialog("Rename group", old, "Save", onConfirm = { vm.rename(old, it); renaming = null }, onDismiss = { renaming = null }) }
    deleting?.let { g ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"$g\"?") },
            text = { Text("The channels stay subscribed; only the group is removed.") },
            confirmButton = { TextButton(onClick = { vm.delete(g); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

/** The newest videos from every channel in [group]. */
@Composable
fun GroupFeedScreen(group: String, onBack: () -> Unit, onVideoClick: (Video) -> Unit, vm: GroupFeedViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(group) { vm.load(group) }
    PlainBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            SimpleTopBar(group, onBack)
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                ui.channels == 0 -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("This group is empty. Open a channel and tap the group's chip to add it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
                    if (ui.failed > 0) item {
                        Text(
                            "${ui.failed} of ${ui.channels} channels could not be loaded.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }
                    itemsIndexed(ui.videos, key = { i, v -> "${v.id}_$i" }) { i, v -> VideoCard(v, onClick = { onVideoClick(v) }, index = i) }
                }
            }
        }
    }
}
