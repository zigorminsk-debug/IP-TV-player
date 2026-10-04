package com.iptvplayer.app.ui.screens.epg

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.iptvplayer.app.R
import com.iptvplayer.app.data.db.EpgSourceEntity
import com.iptvplayer.app.data.model.RefreshResult
import com.iptvplayer.app.di.ServiceLocator
import com.iptvplayer.app.ui.components.EmptyState
import com.iptvplayer.app.ui.components.tvFocusable
import com.iptvplayer.app.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EpgSourcesViewModel(app: Application) : AndroidViewModel(app) {

    private val c = ServiceLocator.instance

    val sources = c.epg.observeSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists = c.playlists.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val refreshingIds = MutableStateFlow<Set<Long>>(emptySet())
    val errors = MutableStateFlow<Map<Long, String>>(emptyMap())

    fun playlistName(playlistId: Long?): String? =
        playlists.value.firstOrNull { it.id == playlistId }?.name

    fun add(name: String, url: String, playlistId: Long?) {
        viewModelScope.launch { c.epg.addSource(name, url, playlistId) }
    }

    fun delete(source: EpgSourceEntity) {
        viewModelScope.launch { c.epg.deleteSource(source.id) }
    }

    fun toggleEnabled(source: EpgSourceEntity) {
        viewModelScope.launch { c.epg.setSourceEnabled(source.id, !source.enabled) }
    }

    fun refresh(source: EpgSourceEntity) {
        viewModelScope.launch {
            refreshingIds.value = refreshingIds.value + source.id
            val result = c.epg.refreshSource(source.id)
            if (result is RefreshResult.Error) {
                errors.value = errors.value + (source.id to (result.message ?: "error"))
            } else {
                errors.value = errors.value - source.id
            }
            refreshingIds.value = refreshingIds.value - source.id
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpgSourcesScreen(navController: NavHostController) {
    val vm: EpgSourcesViewModel = viewModel()
    val sources by vm.sources.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val refreshingIds by vm.refreshingIds.collectAsState()
    val errors by vm.errors.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<EpgSourceEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.epg_sources)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_epg_source)) },
            )
        },
    ) { padding ->
        if (sources.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.DateRange,
                title = stringResource(R.string.epg_sources_empty),
                hint = stringResource(R.string.epg_sources_empty_hint),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(sources, key = { it.id }) { source ->
                    val refreshing = source.id in refreshingIds
                    val error = errors[source.id]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .tvFocusable()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = source.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val plName = vm.playlistName(source.playlistId)
                                val updated = if (source.lastUpdate > 0) {
                                    Format.relativeAgo(source.lastUpdate)
                                } else {
                                    stringResource(R.string.never_updated)
                                }
                                Text(
                                    text = (plName ?: stringResource(R.string.epg_global)) +
                                        " · $updated",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                error?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (refreshing) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                )
                            } else {
                                IconButton(onClick = { vm.refresh(source) }) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.refresh),
                                    )
                                }
                            }
                            IconButton(onClick = { deleteTarget = source }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                )
                            }
                            Switch(
                                checked = source.enabled,
                                onCheckedChange = { vm.toggleEnabled(source) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddEpgSourceDialog(
            playlistNames = playlists.map { it.id to it.name },
            onDismiss = { showAdd = false },
            onSave = { name, url, playlistId ->
                vm.add(name, url, playlistId)
                showAdd = false
            },
        )
    }

    deleteTarget?.let { source ->
        com.iptvplayer.app.ui.components.ConfirmDialog(
            title = stringResource(R.string.delete),
            text = source.name,
            onConfirm = {
                vm.delete(source)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
            confirmText = stringResource(R.string.delete),
        )
    }
}

@Composable
private fun AddEpgSourceDialog(
    playlistNames: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onSave: (String, String, Long?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var selectedPlaylist by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_epg_source)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.field_epg_url)) },
                    singleLine = true,
                )
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                )
                Spacer(Modifier.padding(8.dp))
                Text(stringResource(R.string.epg_playlist_link))
                Row {
                    TextButton(onClick = { selectedPlaylist = null }) {
                        Text(
                            stringResource(R.string.epg_global),
                            color = if (selectedPlaylist == null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    playlistNames.take(4).forEach { (id, plName) ->
                        TextButton(onClick = { selectedPlaylist = id }) {
                            Text(
                                plName,
                                color = if (selectedPlaylist == id) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank()) onSave(name, url.trim(), selectedPlaylist)
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
