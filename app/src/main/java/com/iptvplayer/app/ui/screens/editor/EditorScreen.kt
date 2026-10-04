package com.iptvplayer.app.ui.screens.editor

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.iptvplayer.app.R
import com.iptvplayer.app.data.db.CategoryEntity
import com.iptvplayer.app.data.db.ChannelEntity
import com.iptvplayer.app.data.model.ChannelKind
import com.iptvplayer.app.di.ServiceLocator
import com.iptvplayer.app.ui.components.EmptyState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Parental-control editor: mark categories and channels as locked (PIN
 * required to open) or hidden (removed from all lists).
 */
class EditorViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {

    private val c = ServiceLocator.instance
    val playlistId: Long = savedState.get<String>("playlistId")?.toLong() ?: 0L

    val categories = c.playlists.observeAllCategories(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val channels = c.playlists.observeAllChannels(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setCategoryLocked(category: CategoryEntity, locked: Boolean) {
        viewModelScope.launch { c.playlists.setCategoryLocked(category.uid, locked) }
    }

    fun setCategoryHidden(category: CategoryEntity, hidden: Boolean) {
        viewModelScope.launch { c.playlists.setCategoryHidden(category.uid, hidden) }
    }

    fun setChannelLocked(channel: ChannelEntity, locked: Boolean) {
        viewModelScope.launch { c.playlists.setChannelLocked(channel.uid, locked) }
    }

    fun setChannelHidden(channel: ChannelEntity, hidden: Boolean) {
        viewModelScope.launch { c.playlists.setChannelHidden(channel.uid, hidden) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(playlistId: Long, navController: NavHostController) {
    val vm: EditorViewModel = viewModel()
    val categories by vm.categories.collectAsState()
    val channels by vm.channels.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.parental_editor)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
        )

        Text(
            text = stringResource(R.string.parental_editor_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (categories.isEmpty() && channels.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Security,
                title = stringResource(R.string.channels_empty_title),
                hint = stringResource(R.string.channels_empty_hint),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SectionLabel(stringResource(R.string.editor_categories))
            }
            items(categories, key = { it.uid }) { category ->
                LockHideRow(
                    title = category.name,
                    badge = kindLabel(category.kind),
                    locked = category.isLocked,
                    hidden = category.isHidden,
                    onLock = { vm.setCategoryLocked(category, it) },
                    onHide = { vm.setCategoryHidden(category, it) },
                )
            }
            item {
                SectionLabel(stringResource(R.string.editor_channels))
            }
            items(channels, key = { it.uid }) { channel ->
                LockHideRow(
                    title = channel.name,
                    badge = kindLabel(channel.kind),
                    locked = channel.isLocked,
                    hidden = channel.isHidden,
                    onLock = { vm.setChannelLocked(channel, it) },
                    onHide = { vm.setChannelHidden(channel, it) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    ChannelKind.LIVE.name -> stringResource(R.string.tab_live)
    ChannelKind.MOVIE.name -> stringResource(R.string.tab_movies)
    ChannelKind.SERIES.name -> stringResource(R.string.tab_series)
    else -> ""
}

@Composable
private fun LockHideRow(
    title: String,
    badge: String,
    locked: Boolean,
    hidden: Boolean,
    onLock: (Boolean) -> Unit,
    onHide: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (badge.isNotBlank()) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = stringResource(R.string.editor_hide),
            style = MaterialTheme.typography.labelSmall,
        )
        Switch(
            checked = hidden,
            onCheckedChange = onHide,
            modifier = Modifier.width(52.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.editor_lock),
            style = MaterialTheme.typography.labelSmall,
        )
        Switch(
            checked = locked,
            onCheckedChange = onLock,
            modifier = Modifier.width(52.dp),
        )
    }
}
