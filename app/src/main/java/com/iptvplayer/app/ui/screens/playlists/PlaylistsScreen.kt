package com.iptvplayer.app.ui.screens.playlists

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.iptvplayer.app.R
import com.iptvplayer.app.data.db.PlaylistEntity
import com.iptvplayer.app.data.model.PlaylistType
import com.iptvplayer.app.data.repo.PlaylistRepository
import com.iptvplayer.app.di.ServiceLocator
import com.iptvplayer.app.ui.Routes
import com.iptvplayer.app.ui.components.ConfirmDialog
import com.iptvplayer.app.ui.components.EmptyState
import com.iptvplayer.app.ui.components.tvFocusable
import com.iptvplayer.app.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(app: Application) : AndroidViewModel(app) {

    private val c = ServiceLocator.instance

    val playlists = c.playlists.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val counts = c.playlists.observeCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val refreshStates = c.playlists.refreshStates

    fun addPlaylist(data: PlaylistRepository.NewPlaylist, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val id = c.playlists.addPlaylist(data)
            val result = c.playlists.refresh(id)
            c.settings.setLastPlaylist(id)
            onDone(result.isSuccess)
        }
    }

    fun updatePlaylist(entity: PlaylistEntity) {
        viewModelScope.launch { c.playlists.updatePlaylist(entity) }
    }

    fun refresh(id: Long) {
        viewModelScope.launch { c.playlists.refresh(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { c.playlists.deletePlaylist(id) }
    }

    fun move(id: Long, up: Boolean) {
        viewModelScope.launch { c.playlists.movePlaylist(id, up) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(navController: NavHostController) {
    val vm: PlaylistsViewModel = viewModel()
    val playlists by vm.playlists.collectAsState()
    val counts by vm.counts.collectAsState()
    val refreshStates by vm.refreshStates.collectAsState()

    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.tab_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_playlist)) },
            )
        },
    ) { padding ->
        if (playlists.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.LiveTv,
                title = stringResource(R.string.playlists_empty_title),
                hint = stringResource(R.string.playlists_empty_hint),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(playlists, key = { it.id }) { pl ->
                    val count = counts.firstOrNull { it.playlistId == pl.id }?.count ?: 0
                    val state = refreshStates[pl.id]
                    PlaylistCard(
                        playlist = pl,
                        channelCount = count,
                        refreshing = state?.running == true,
                        error = state?.error,
                        onClick = {
                            navController.navigate("${Routes.CHANNELS}/${pl.id}")
                        },
                        onRefresh = { vm.refresh(pl.id) },
                        onEdit = { editTarget = pl },
                        onDelete = { deleteTarget = pl },
                        onMoveUp = { vm.move(pl.id, up = true) },
                        onMoveDown = { vm.move(pl.id, up = false) },
                        onParental = {
                            navController.navigate("${Routes.EDITOR}/${pl.id}")
                        },
                    )
                }
            }
        }
    }

    if (showAdd || editTarget != null) {
        PlaylistEditDialog(
            initial = editTarget,
            onDismiss = {
                showAdd = false
                editTarget = null
            },
            onSave = { data ->
                val target = editTarget
                if (target == null) {
                    vm.addPlaylist(data) { }
                } else {
                    vm.updatePlaylist(
                        target.copy(
                            name = data.name,
                            url = data.url,
                            username = data.username.takeIf { it.isNotBlank() },
                            password = data.password.takeIf { it.isNotBlank() },
                            preferHls = data.preferHls,
                            refreshHours = data.refreshHours,
                        ),
                    )
                }
                showAdd = false
                editTarget = null
            },
        )
    }

    deleteTarget?.let { pl ->
        ConfirmDialog(
            title = stringResource(R.string.delete_playlist_confirm, pl.name),
            text = stringResource(R.string.delete_playlist_text),
            onConfirm = {
                vm.delete(pl.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            confirmText = stringResource(R.string.delete),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    playlist: PlaylistEntity,
    channelCount: Int,
    refreshing: Boolean,
    error: String?,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onParental: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isXc = playlist.type == PlaylistType.XTREAM.name

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable()
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isXc) Icons.Filled.Dns else Icons.Filled.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val typeLabel = stringResource(
                        if (isXc) R.string.playlist_type_xtream else R.string.playlist_type_m3u,
                    )
                    val updated = if (playlist.lastUpdate > 0) {
                        stringResource(R.string.updated_ago, Format.relativeAgo(playlist.lastUpdate))
                    } else {
                        stringResource(R.string.never_updated)
                    }
                    Text(
                        text = "$typeLabel · " +
                            stringResource(R.string.channels_count_fmt, channelCount) + " · $updated",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (refreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier.width(48.dp).height(4.dp).padding(start = 8.dp),
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                        )
                    }
                }
                // overflow menu
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.edit),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.refresh)) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                        onClick = { menuOpen = false; onRefresh() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.parental_editor)) },
                        leadingIcon = { Icon(Icons.Filled.Security, null) },
                        onClick = { menuOpen = false; onParental() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_up)) },
                        leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                        onClick = { menuOpen = false; onMoveUp() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_down)) },
                        leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                        onClick = { menuOpen = false; onMoveDown() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.refresh_error_fmt, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Create / edit playlist dialog. */
@Composable
private fun PlaylistEditDialog(
    initial: PlaylistEntity?,
    onDismiss: () -> Unit,
    onSave: (PlaylistRepository.NewPlaylist) -> Unit,
) {
    var type by remember {
        mutableStateOf(
            if (initial?.type == PlaylistType.XTREAM.name) PlaylistType.XTREAM else PlaylistType.M3U,
        )
    }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var epgUrl by remember { mutableStateOf("") }
    var preferHls by remember { mutableStateOf(initial?.preferHls ?: true) }
    var refreshHours by remember { mutableStateOf(initial?.refreshHours ?: 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.playlist_form_title_new
                    else R.string.playlist_form_title_edit,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == PlaylistType.M3U,
                        onClick = { type = PlaylistType.M3U },
                        label = { Text(stringResource(R.string.playlist_type_m3u)) },
                    )
                    FilterChip(
                        selected = type == PlaylistType.XTREAM,
                        onClick = { type = PlaylistType.XTREAM },
                        label = { Text(stringResource(R.string.playlist_type_xtream)) },
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = {
                        Text(
                            stringResource(
                                if (type == PlaylistType.XTREAM) R.string.field_server
                                else R.string.field_url,
                            ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (type == PlaylistType.XTREAM) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.field_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.field_password)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.prefer_hls),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(checked = preferHls, onCheckedChange = { preferHls = it })
                    }
                } else {
                    OutlinedTextField(
                        value = epgUrl,
                        onValueChange = { epgUrl = it },
                        label = { Text(stringResource(R.string.field_epg_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    stringResource(R.string.auto_refresh),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 6, 12, 24).forEach { hours ->
                        FilterChip(
                            selected = refreshHours == hours,
                            onClick = { refreshHours = hours },
                            label = {
                                Text(
                                    if (hours == 0) stringResource(R.string.off)
                                    else stringResource(R.string.hours_fmt, hours),
                                )
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = name.ifBlank {
                        url.trim().substringAfter("//").substringBefore('/').ifBlank { "Playlist" }
                    }
                    if (url.isBlank()) return@TextButton
                    onSave(
                        PlaylistRepository.NewPlaylist(
                            name = finalName,
                            type = type,
                            url = url.trim(),
                            username = username.trim(),
                            password = password.trim(),
                            preferHls = preferHls,
                            refreshHours = refreshHours,
                            epgUrl = epgUrl.trim(),
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
