package com.iptvplayer.app.ui.screens.search

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.iptvplayer.app.R
import com.iptvplayer.app.data.db.ChannelEntity
import com.iptvplayer.app.data.db.ProgrammeEntity
import com.iptvplayer.app.di.ServiceLocator
import com.iptvplayer.app.ui.Routes
import com.iptvplayer.app.ui.components.ChannelLogo
import com.iptvplayer.app.ui.components.EmptyState
import com.iptvplayer.app.ui.components.tvFocusable
import com.iptvplayer.app.util.Format
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val c = ServiceLocator.instance

    val query = MutableStateFlow("")
    val channelResults = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val programmeResults = MutableStateFlow<List<Pair<ProgrammeEntity, ChannelEntity?>>>(emptyList())
    val searching = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            query.debounce(300).collect { q ->
                val trimmed = q.trim()
                if (trimmed.length < 2) {
                    channelResults.value = emptyList()
                    programmeResults.value = emptyList()
                } else {
                    searching.value = true
                    channelResults.value = c.playlists.searchChannels(trimmed)
                    val progs = runCatching { c.epg.searchTitles(trimmed) }
                        .getOrDefault(emptyList())
                    programmeResults.value = progs.map { p ->
                        p to c.playlists.channelByEpgId(p.epgId)
                    }
                    searching.value = false
                }
            }
        }
    }

    /** Plays a search result: queue = same playlist & kind. */
    fun playChannel(channel: ChannelEntity, onReady: () -> Unit) {
        viewModelScope.launch {
            val list = c.playlists.channelsForPlaylist(channel.playlistId)
                .filter { it.kind == channel.kind }
            val index = list.indexOfFirst { it.uid == channel.uid }
            c.playbackQueue.set(
                if (index >= 0) list else listOf(channel),
                index.coerceAtLeast(0),
                0,
            )
            onReady()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(navController: NavHostController) {
    val vm: SearchViewModel = viewModel()
    val query by vm.query.collectAsState()
    val channels by vm.channelResults.collectAsState()
    val programmes by vm.programmeResults.collectAsState()

    var searchText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun onChannel(channel: ChannelEntity) {
        if (channel.kind == "SERIES") {
            navController.navigate("${Routes.SERIES}/${channel.playlistId}/${channel.uid}")
        } else {
            vm.playChannel(channel) { navController.navigate(Routes.PLAYER) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.tab_search)) })
        OutlinedTextField(
            value = searchText,
            onValueChange = {
                searchText = it
                vm.query.value = it
            },
            label = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
        )

        if (query.trim().length < 2) {
            EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.search_start_typing),
                hint = stringResource(R.string.search_start_typing_hint),
            )
        } else if (channels.isEmpty() && programmes.isEmpty()) {
            EmptyState(icon = Icons.Filled.Search, title = stringResource(R.string.search_empty))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                if (channels.isNotEmpty()) {
                    item {
                        SectionTitle(stringResource(R.string.section_channels))
                    }
                    items(channels, key = { "ch_" + it.uid }) { channel ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusable()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon = when (channel.kind) {
                                "MOVIE" -> Icons.Filled.Movie
                                "SERIES" -> Icons.Filled.VideoLibrary
                                else -> Icons.Filled.LiveTv
                            }
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (programmes.isNotEmpty()) {
                    item {
                        SectionTitle(stringResource(R.string.section_programmes))
                    }
                    items(programmes, key = { (p, _) -> "pr_${p.id}" }) { (programme, channel) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusable()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = programme.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = (channel?.name ?: "—") +
                                        " · " + Format.time(programme.start),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
