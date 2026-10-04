package com.iptvplayer.app.ui.screens.channels

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.iptvplayer.app.R
import com.iptvplayer.app.data.db.CategoryEntity
import com.iptvplayer.app.data.db.ChannelEntity
import com.iptvplayer.app.data.db.WatchProgressEntity
import com.iptvplayer.app.data.model.ChannelKind
import com.iptvplayer.app.data.model.ChannelSort
import com.iptvplayer.app.data.model.NowNext
import com.iptvplayer.app.data.model.PlaylistType
import com.iptvplayer.app.di.ServiceLocator
import com.iptvplayer.app.player.ExternalPlayer
import com.iptvplayer.app.ui.Routes
import com.iptvplayer.app.ui.components.ChannelLogo
import com.iptvplayer.app.ui.components.EmptyState
import com.iptvplayer.app.ui.components.LoadingIndicator
import com.iptvplayer.app.ui.components.PinDialog
import com.iptvplayer.app.ui.components.tvFocusable
import com.iptvplayer.app.util.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {

    private val c = ServiceLocator.instance
    val playlistId: Long = savedState.get<String>("playlistId")?.toLong() ?: 0L

    val playlist = MutableStateFlow<com.iptvplayer.app.data.db.PlaylistEntity?>(null)
    val kind = MutableStateFlow(ChannelKind.LIVE)
    val selectedCategory = MutableStateFlow<String?>(null) // null = all, FAV = favorites
    val searchOpen = MutableStateFlow(false)
    val searchQuery = MutableStateFlow("")
    val sort = MutableStateFlow(ChannelSort.ORDER)
    val nowNext = MutableStateFlow<Map<String, NowNext>>(emptyMap())
    val refreshRunning = MutableStateFlow(false)
    val refreshError = MutableStateFlow<String?>(null)
    val parentalEnabled = MutableStateFlow(false)

    val showLogos = c.settings.settings.map { it.showLogos }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    data class Row(
        val channel: ChannelEntity,
        val locked: Boolean,
    )

    private val channelsFlow = kind.flatMapLatest { k ->
        c.playlists.observeChannels(playlistId, k)
    }
    private val categoriesFlow = kind.flatMapLatest { k ->
        c.playlists.observeCategories(playlistId, k)
    }

    val categories = categoriesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rows = combine(
        channelsFlow,
        categoriesFlow,
        selectedCategory,
        searchQuery,
        sort,
    ) { channels, cats, selCat, query, sortMode ->
        val hiddenCats = cats.filter { it.isHidden }.map { it.uid }.toSet()
        val lockedCats = cats.filter { it.isLocked }.map { it.uid }.toSet()
        var list: List<ChannelEntity> =
            channels.filter { ch -> !ch.isHidden && ch.categoryId !in hiddenCats }
        list = when (selCat) {
            FAV -> list.filter { it.isFavorite }
            null -> list
            else -> list.filter { it.categoryId == selCat }
        }
        val q = query.trim()
        if (q.isNotEmpty()) {
            list = list.filter { it.name.contains(q, ignoreCase = true) }
        }
        list = when (sortMode) {
            ChannelSort.ORDER -> list.sortedBy { it.orderNum }
            ChannelSort.NAME_ASC -> list.sortedBy { it.name.lowercase() }
            ChannelSort.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
        }
        list.map { Row(it, it.isLocked || (it.categoryId != null && it.categoryId in lockedCats)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            playlist.value = c.playlists.playlist(playlistId)
            sort.value = c.settings.settings.first().defaultSort
        }
        viewModelScope.launch {
            c.settings.settings.collect { parentalEnabled.value = it.parentalEnabled }
        }
        viewModelScope.launch {
            channelsFlow.collect { chs ->
                if (kind.value == ChannelKind.LIVE && chs.isNotEmpty()) {
                    nowNext.value = runCatching { c.epg.nowNext(chs) }.getOrDefault(emptyMap())
                }
            }
        }
    }

    fun setKind(k: ChannelKind) {
        kind.value = k
        selectedCategory.value = null
    }

    fun isCategoryLocked(cat: CategoryEntity): Boolean =
        parentalEnabled.value && cat.isLocked && !c.parental.isUnlocked

    fun isBlocked(channel: ChannelEntity): Boolean {
        if (!parentalEnabled.value) return false
        if (c.parental.isUnlocked) return false
        val catLocked = categories.value.any { it.uid == channel.categoryId && it.isLocked }
        return channel.isLocked || catLocked
    }

    fun verifyPin(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(c.parental.verifyAndUnlock(pin)) }
    }

    /** Puts the current on-screen list into the playback queue and starts. */
    fun playChannel(channel: ChannelEntity, startMs: Long = 0) {
        val list = rows.value.map { it.channel }
        val index = list.indexOfFirst { it.uid == channel.uid }
        if (index >= 0) {
            c.playbackQueue.set(list, index, startMs)
        } else {
            c.playbackQueue.set(listOf(channel), 0, startMs)
        }
    }

    fun progressFor(channel: ChannelEntity, onResult: (WatchProgressEntity?) -> Unit) {
        viewModelScope.launch { onResult(c.progress.get(channel.uid)) }
    }

    fun toggleFavorite(channel: ChannelEntity) {
        viewModelScope.launch { c.playlists.setFavorite(channel.uid, !channel.isFavorite) }
    }

    fun toggleHidden(channel: ChannelEntity) {
        viewModelScope.launch { c.playlists.setChannelHidden(channel.uid, !channel.isHidden) }
    }

    fun toggleLock(channel: ChannelEntity) {
        viewModelScope.launch { c.playlists.setChannelLocked(channel.uid, !channel.isLocked) }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshRunning.value = true
            val result = c.playlists.refresh(playlistId)
            refreshError.value =
                (result as? com.iptvplayer.app.data.model.RefreshResult.Error)?.message
            refreshRunning.value = false
        }
    }

    companion object {
        const val FAV = "fav"
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChannelsScreen(playlistId: Long, navController: NavHostController) {
    val vm: ChannelsViewModel = viewModel()
    val context = LocalContext.current

    val playlist by vm.playlist.collectAsState()
    val rows by vm.rows.collectAsState()
    val categories by vm.categories.collectAsState()
    val kind by vm.kind.collectAsState()
    val selectedCategory by vm.selectedCategory.collectAsState()
    val searchOpen by vm.searchOpen.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val sort by vm.sort.collectAsState()
    val nowNext by vm.nowNext.collectAsState()
    val showLogos by vm.showLogos.collectAsState()
    val parentalEnabled by vm.parentalEnabled.collectAsState()
    val refreshRunning by vm.refreshRunning.collectAsState()
    val refreshError by vm.refreshError.collectAsState()

    var pinChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var actionChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var infoChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var resumeFor by remember { mutableStateOf<Pair<ChannelEntity, WatchProgressEntity>?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var pinCategory by remember { mutableStateOf<CategoryEntity?>(null) }

    val isXc = playlist?.type == PlaylistType.XTREAM.name
    val kindTabs = remember {
        if (isXc) listOf(ChannelKind.LIVE, ChannelKind.MOVIE, ChannelKind.SERIES)
        else listOf(ChannelKind.LIVE)
    }

    fun tryPlay(channel: ChannelEntity, startMs: Long = 0) {
        if (vm.isBlocked(channel)) {
            pinChannel = channel
        } else {
            vm.playChannel(channel, startMs)
            navController.navigate(Routes.PLAYER)
        }
    }

    fun onChannelClick(channel: ChannelEntity) {
        if (channel.kind == ChannelKind.SERIES.name) {
            if (vm.isBlocked(channel)) {
                pinChannel = channel
            } else {
                navController.navigate("${Routes.SERIES}/${channel.playlistId}/${channel.uid}")
            }
            return
        }
        if (channel.kind == ChannelKind.MOVIE.name) {
            vm.progressFor(channel) { p ->
                if (p != null && p.positionMs > 30_000 &&
                    (p.durationMs <= 0 || p.positionMs < p.durationMs - 30_000)
                ) {
                    resumeFor = channel to p
                } else {
                    tryPlay(channel)
                }
            }
        } else {
            tryPlay(channel)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = playlist?.name ?: stringResource(R.string.app_name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
            actions = {
                if (kind == ChannelKind.LIVE) {
                    IconButton(onClick = {
                        navController.navigate("${Routes.GUIDE}/$playlistId")
                    }) {
                        Icon(
                            Icons.Filled.DateRange,
                            contentDescription = stringResource(R.string.guide),
                        )
                    }
                }
                IconButton(onClick = {
                    vm.searchOpen.value = !searchOpen
                    if (!searchOpen) vm.searchQuery.value = ""
                }) {
                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                }
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.sort))
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        val options = listOf(
                            ChannelSort.ORDER to R.string.sort_default,
                            ChannelSort.NAME_ASC to R.string.sort_name_asc,
                            ChannelSort.NAME_DESC to R.string.sort_name_desc,
                        )
                        options.forEach { (mode, res) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(res)) },
                                onClick = {
                                    vm.sort.value = mode
                                    sortMenuOpen = false
                                },
                                trailingIcon = {
                                    if (sort == mode) {
                                        Icon(Icons.Filled.Star, contentDescription = null)
                                    }
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                }
            },
        )

        if (refreshRunning) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }
        refreshError?.let { err ->
            Text(
                text = stringResource(R.string.refresh_error_fmt, err),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (kindTabs.size > 1) {
            TabRow(selectedTabIndex = kindTabs.indexOf(kind).coerceAtLeast(0)) {
                kindTabs.forEach { k ->
                    val (labelRes, icon) = when (k) {
                        ChannelKind.LIVE -> R.string.tab_live to Icons.Filled.LiveTv
                        ChannelKind.MOVIE -> R.string.tab_movies to Icons.Filled.Movie
                        ChannelKind.SERIES -> R.string.tab_series to Icons.Filled.VideoLibrary
                    }
                    Tab(
                        selected = kind == k,
                        onClick = { vm.setKind(k) },
                        text = { Text(stringResource(labelRes)) },
                        icon = { Icon(icon, contentDescription = null) },
                    )
                }
            }
        }

        if (searchOpen) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { vm.searchQuery.value = it },
                label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // Category chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { vm.selectedCategory.value = null },
                    label = { Text(stringResource(R.string.category_all)) },
                )
            }
            item {
                FilterChip(
                    selected = selectedCategory == ChannelsViewModel.FAV,
                    onClick = {
                        vm.selectedCategory.value =
                            if (selectedCategory == ChannelsViewModel.FAV) null
                            else ChannelsViewModel.FAV
                    },
                    label = { Text(stringResource(R.string.favorites)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Star, contentDescription = null, Modifier.size(16.dp))
                    },
                )
            }
            items(categories.filter { !it.isHidden }, key = { it.uid }) { cat ->
                FilterChip(
                    selected = selectedCategory == cat.uid,
                    onClick = {
                        if (vm.isCategoryLocked(cat)) {
                            pinCategory = cat
                        } else {
                            vm.selectedCategory.value =
                                if (selectedCategory == cat.uid) null else cat.uid
                        }
                    },
                    label = { Text(cat.name) },
                )
            }
        }

        when {
            rows.isEmpty() && searchQuery.isNotBlank() -> EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.search_empty),
            )

            rows.isEmpty() && categories.isEmpty() -> EmptyState(
                icon = Icons.Filled.LiveTv,
                title = stringResource(R.string.channels_empty_title),
                hint = stringResource(R.string.channels_empty_hint),
            )

            rows.isEmpty() -> EmptyState(
                icon = Icons.Filled.LiveTv,
                title = stringResource(R.string.no_channels_in_category),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(rows, key = { it.channel.uid }) { row ->
                    ChannelRowItem(
                        row = row,
                        nowNext = nowNext[row.channel.uid],
                        showLogos = showLogos,
                        showLock = parentalEnabled && row.locked,
                        showFavorite = row.channel.isFavorite,
                        onClick = { onChannelClick(row.channel) },
                        onLongClick = { actionChannel = row.channel },
                    )
                }
            }
        }
    }

    // ---- PIN gate
    pinChannel?.let { channel ->
        PinDialog(
            onVerify = { pin, cb -> vm.verifyPin(pin, cb) },
            onSuccess = {
                pinChannel = null
                tryPlay(channel)
            },
            onDismiss = { pinChannel = null },
        )
    }

    // ---- resume dialog for VOD
    resumeFor?.let { (channel, progress) ->
        AlertDialog(
            onDismissRequest = { resumeFor = null },
            title = { Text(stringResource(R.string.resume_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.resume_text,
                        Format.msToClock(progress.positionMs),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resumeFor = null
                    tryPlay(channel, progress.positionMs)
                }) { Text(stringResource(R.string.resume_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    resumeFor = null
                    tryPlay(channel, 0)
                }) { Text(stringResource(R.string.resume_from_start)) }
            },
        )
    }

    // ---- long-press actions
    actionChannel?.let { channel ->
        AlertDialog(
            onDismissRequest = { actionChannel = null },
            title = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    ActionRow(Icons.Filled.OpenInNew, stringResource(R.string.play_external)) {
                        ExternalPlayer.play(context, channel.url)
                        actionChannel = null
                    }
                    ActionRow(Icons.Filled.ContentCopy, stringResource(R.string.copy_url)) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText("url", channel.url),
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                        actionChannel = null
                    }
                    ActionRow(
                        if (channel.isFavorite) Icons.Filled.FavoriteBorder
                        else Icons.Filled.Favorite,
                        stringResource(
                            if (channel.isFavorite) R.string.remove_favorite
                            else R.string.add_favorite,
                        ),
                    ) {
                        vm.toggleFavorite(channel)
                        actionChannel = null
                    }
                    ActionRow(Icons.Filled.Visibility, stringResource(R.string.hide_channel)) {
                        vm.toggleHidden(channel)
                        actionChannel = null
                    }
                    if (parentalEnabled) {
                        ActionRow(Icons.Filled.Lock, stringResource(R.string.lock_channel)) {
                            vm.toggleLock(channel)
                            actionChannel = null
                        }
                    }
                    ActionRow(Icons.Filled.Info, stringResource(R.string.info)) {
                        infoChannel = channel
                        actionChannel = null
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionChannel = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    infoChannel?.let { channel ->
        val sb = StringBuilder()
        channel.plot?.let { sb.append(it).append("\n\n") }
        if (channel.rating != null) {
            sb.append(stringResource(R.string.rating_fmt, channel.rating ?: 0.0)).append("\n")
        }
        channel.releaseDate?.let { sb.append(it) }
        com.iptvplayer.app.ui.components.InfoDialog(
            title = channel.name,
            text = sb.toString(),
            onDismiss = { infoChannel = null },
        )
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRowItem(
    row: ChannelsViewModel.Row,
    nowNext: NowNext?,
    showLogos: Boolean,
    showLock: Boolean,
    showFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.channel.num > 0) {
            Text(
                text = row.channel.num.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
            )
        }
        ChannelLogo(
            url = row.channel.logo,
            show = showLogos,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.channel.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val now = nowNext?.now
            if (now != null) {
                Text(
                    text = "${Format.time(now.start)}  ${now.title}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showFavorite) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        if (showLock) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = stringResource(R.string.locked),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
showLock) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = stringResource(R.string.locked),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
