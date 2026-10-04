package com.iptvplayer.app.ui.screens.series

import android.app.Application
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.iptvplayer.app.R
import com.iptvplayer.app.data.db.ChannelEntity
import com.iptvplayer.app.data.db.WatchProgressEntity
import com.iptvplayer.app.data.model.PlaylistType
import com.iptvplayer.app.data.remote.XtreamClient
import com.iptvplayer.app.di.ServiceLocator
import com.iptvplayer.app.ui.Routes
import com.iptvplayer.app.ui.components.ChannelLogo
import com.iptvplayer.app.ui.components.EmptyState
import com.iptvplayer.app.ui.components.LoadingIndicator
import com.iptvplayer.app.ui.components.tvFocusable
import com.iptvplayer.app.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SeriesViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {

    private val c = ServiceLocator.instance
    private val playlistId: Long = savedState.get<String>("playlistId")?.toLong() ?: 0L
    private val channelUid: String = savedState.get<String>("channelUid") ?: ""

    val channel = MutableStateFlow<ChannelEntity?>(null)
    val info = MutableStateFlow<XtreamClient.XcSeriesInfo?>(null)
    val selectedSeason = MutableStateFlow<Int?>(null)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private var account: XtreamClient.XcAccount? = null

    init {
        viewModelScope.launch {
            val ch = c.playlists.channelByUid(channelUid)
            channel.value = ch
            val pl = c.playlists.playlist(playlistId)
            if (ch != null && pl != null && pl.type == PlaylistType.XTREAM.name) {
                account = XtreamClient.XcAccount(
                    serverUrl = pl.url,
                    username = pl.username.orEmpty(),
                    password = pl.password.orEmpty(),
                )
                val seriesId = ch.seriesId ?: ch.remoteId.toLongOrNull()
                if (seriesId != null) {
                    loading.value = true
                    runCatching { c.playlists.xtreamClient.getSeriesInfo(account!!, seriesId) }
                        .onSuccess { series ->
                            if (series != null) {
                                info.value = series
                                selectedSeason.value = series.seasons.keys.firstOrNull()
                            }
                        }
                        .onFailure { error.value = it.message ?: "load error" }
                    loading.value = false
                }
            }
        }
    }

    fun episodes(): List<XtreamClient.XcEpisode> =
        info.value?.seasons?.get(selectedSeason.value) ?: emptyList()

    /** Builds a synthetic playback item for one episode. */
    private fun synthetic(
        ch: ChannelEntity,
        ep: XtreamClient.XcEpisode,
    ): ChannelEntity = ChannelEntity(
        uid = "ep:${ep.id}",
        playlistId = ch.playlistId,
        kind = "EPISODE",
        remoteId = ep.id.toString(),
        name = "S%02dE%02d".format(ep.season, ep.episodeNum) +
            (if (ep.title.isNotBlank()) " · ${ep.title}" else "") +
            " · ${ch.name}",
        url = account?.let { XtreamClient.seriesEpisodeUrl(it, ep.id, ep.containerExt) }
            ?: ch.url,
        logo = ep.poster ?: ch.logo,
        num = ep.episodeNum,
        orderNum = ep.episodeNum,
        containerExt = ep.containerExt,
    )

    fun playEpisode(ep: XtreamClient.XcEpisode, startMs: Long = 0) {
        val ch = channel.value ?: return
        val all = episodes().map { synthetic(ch, it) }
        val index = all.indexOfFirst { it.remoteId == ep.id.toString() }
        c.playbackQueue.set(
            if (index >= 0) all else listOf(synthetic(ch, ep)),
            index.coerceAtLeast(0),
            startMs,
        )
    }

    fun progressFor(ep: XtreamClient.XcEpisode, onResult: (WatchProgressEntity?) -> Unit) {
        viewModelScope.launch { onResult(c.progress.get("ep:${ep.id}")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(playlistId: Long, channelUid: String, navController: NavHostController) {
    val vm: SeriesViewModel = viewModel()
    val channel by vm.channel.collectAsState()
    val info by vm.info.collectAsState()
    val selectedSeason by vm.selectedSeason.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()

    var resumeFor by remember {
        mutableStateOf<Pair<XtreamClient.XcEpisode, WatchProgressEntity>?>(null)
    }

    fun tryPlay(ep: XtreamClient.XcEpisode, startMs: Long = 0) {
        vm.playEpisode(ep, startMs)
        navController.navigate(Routes.PLAYER)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = info?.name ?: channel?.name ?: "",
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
        )

        when {
            loading -> LoadingIndicator()

            error != null -> EmptyState(
                icon = Icons.Filled.VideoLibrary,
                title = stringResource(R.string.series_load_error),
                hint = error,
            )

            info == null -> EmptyState(
                icon = Icons.Filled.VideoLibrary,
                title = stringResource(R.string.series_empty),
            )

            else -> {
                val series = info!!
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(Modifier.padding(16.dp)) {
                        ChannelLogo(
                            url = series.poster ?: channel?.logo,
                            modifier = Modifier.size(120.dp, 180.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                text = series.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            series.genre?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            series.rating?.let {
                                Text(
                                    stringResource(R.string.rating_fmt, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            series.plot?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp),
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Season selector
                if (series.seasons.keys.size > 1) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(series.seasons.keys.toList()) { season ->
                            FilterChip(
                                selected = selectedSeason == season,
                                onClick = { vm.selectedSeason.value = season },
                                label = {
                                    Text(stringResource(R.string.season_fmt, season))
                                },
                            )
                        }
                    }
                }

                val episodes = remember(selectedSeason, series) {
                    series.seasons[selectedSeason].orEmpty()
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(episodes, key = { _, ep -> ep.id }) { _, ep ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusable()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "E%02d".format(ep.episodeNum) +
                                        (if (ep.title.isNotBlank()) " · ${ep.title}" else ""),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                ep.duration?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = {
                                vm.progressFor(ep) { p ->
                                    if (p != null && p.positionMs > 30_000 &&
                                        (p.durationMs <= 0 || p.positionMs < p.durationMs - 30_000)
                                    ) {
                                        resumeFor = ep to p
                                    } else {
                                        tryPlay(ep)
                                    }
                                }
                            }) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = stringResource(R.string.watch),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    resumeFor?.let { (ep, progress) ->
        AlertDialog(
            onDismissRequest = { resumeFor = null },
            title = { Text(stringResource(R.string.resume_title)) },
            text = {
                Text(stringResource(R.string.resume_text, Format.msToClock(progress.positionMs)))
            },
            confirmButton = {
                TextButton(onClick = {
                    resumeFor = null
                    tryPlay(ep, progress.positionMs)
                }) { Text(stringResource(R.string.resume_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    resumeFor = null
                    tryPlay(ep, 0)
                }) { Text(stringResource(R.string.resume_from_start)) }
            },
        )
    }
}
