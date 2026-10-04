package com.iptvplayer.app.ui.screens.guide

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.iptvplayer.app.R
import com.iptvplayer.app.data.db.ChannelEntity
import com.iptvplayer.app.data.db.ProgrammeEntity
import com.iptvplayer.app.di.ServiceLocator
import com.iptvplayer.app.player.Catchup
import com.iptvplayer.app.ui.Routes
import com.iptvplayer.app.ui.components.EmptyState
import com.iptvplayer.app.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class GuideViewModel(app: Application, savedState: SavedStateHandle) : AndroidViewModel(app) {

    private val c = ServiceLocator.instance
    val playlistId: Long = savedState.get<String>("playlistId")?.toLong() ?: 0L

    val channels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val dayOffset = MutableStateFlow(0)
    val schedule = MutableStateFlow<Map<String, List<ProgrammeEntity>>>(emptyMap())
    val loading = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            channels.value = c.playlists.channelsForPlaylist(playlistId)
                .filter { it.kind == "LIVE" }
            loadSchedule()
        }
        viewModelScope.launch {
            dayOffset.collect { loadSchedule() }
        }
    }

    fun loadSchedule() {
        viewModelScope.launch {
            loading.value = true
            val start = Format.dayStartEpochSec(dayOffset.value)
            val end = Format.dayEndEpochSec(dayOffset.value)
            schedule.value = runCatching {
                c.epg.daySchedule(channels.value, start, end)
            }.getOrDefault(emptyMap())
            loading.value = false
        }
    }

    /**
     * Starts playback from a guide programme: catch-up url for past
     * programmes (when available) or the live stream otherwise.
     */
    fun playFromProgramme(channel: ChannelEntity, programme: ProgrammeEntity) {
        val nowSec = System.currentTimeMillis() / 1000
        val catchupUrl = Catchup.buildUrl(channel, programme.start, nowSec, nowSec)
        val item = if (catchupUrl != null) {
            channel.copy(url = catchupUrl, catchupAvailable = false)
        } else {
            channel
        }
        val others = channels.value.filter { it.uid != channel.uid }
        ServiceLocator.instance.playbackQueue.set(listOf(item) + others, 0, 0)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(playlistId: Long, navController: NavHostController) {
    val vm: GuideViewModel = viewModel()
    val channels by vm.channels.collectAsState()
    val dayOffset by vm.dayOffset.collectAsState()
    val schedule by vm.schedule.collectAsState()
    val loading by vm.loading.collectAsState()

    var programmeDetails by remember { mutableStateOf<ProgrammeEntity?>(null) }
    var detailsChannel by remember { mutableStateOf<ChannelEntity?>(null) }

    val density = LocalDensity.current
    val pxPerMin = with(density) { 2.dp.toPx() }
    val dayWidthDp = with(density) { (24 * 60 * pxPerMin).toDp() }
    val nameCellWidth = 140.dp

    val hScroll = rememberScrollState()

    val dayStart = Format.dayStartEpochSec(dayOffset)
    val nowSec = System.currentTimeMillis() / 1000

    // Scroll to "now" when the current day is selected.
    LaunchedEffect(dayOffset) {
        if (dayOffset == 0) {
            val minutesNow = (nowSec - dayStart) / 60
            hScroll.scrollTo(
                (minutesNow * pxPerMin - 300 * density.density).toInt().coerceAtLeast(0),
            )
        } else {
            hScroll.scrollTo(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.guide)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
        )

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        // Day selector
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            itemsIndexed((0 until 7).toList()) { _, offset ->
                val label = when (offset) {
                    0 -> stringResource(R.string.today)
                    1 -> stringResource(R.string.tomorrow)
                    else -> Format.date(Format.dayStartEpochSec(offset))
                }
                FilterChip(
                    selected = dayOffset == offset,
                    onClick = { vm.dayOffset.value = offset },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        if (channels.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.DateRange,
                title = stringResource(R.string.guide_empty),
                hint = stringResource(R.string.guide_empty_hint),
            )
        } else {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(hScroll),
                ) {
                    // Time ruler
                    Box(
                        Modifier
                            .width(dayWidthDp + nameCellWidth)
                            .height(32.dp),
                    ) {
                        for (hour in 0..23) {
                            val x = nameCellWidth +
                                with(density) { (hour * 60 * pxPerMin).toDp() }
                            Text(
                                text = "%02d:00".format(hour),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .offset(x = x)
                                    .padding(start = 4.dp),
                            )
                        }
                    }

                    LazyColumn(Modifier.weight(1f)) {
                        items(channels, key = { it.uid }) { channel ->
                            GuideRow(
                                channel = channel,
                                programmes = schedule[channel.uid] ?: emptyList(),
                                dayStart = dayStart,
                                pxPerMin = pxPerMin,
                                nameCellWidth = nameCellWidth,
                                dayWidthDp = dayWidthDp,
                                nowSec = nowSec,
                                onProgrammeClick = { programme ->
                                    detailsChannel = channel
                                    programmeDetails = programme
                                },
                            )
                        }
                    }
                }

                // "Now" line
                if (dayOffset == 0) {
                    Canvas(Modifier.fillMaxSize()) {
                        val minutesNow = (nowSec - dayStart) / 60f
                        val x = minutesNow * pxPerMin - hScroll.value
                        if (x in 0f..size.width + 200f) {
                            drawLine(
                                color = Color(0xFF22D3EE),
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 3f,
                            )
                        }
                    }
                }
            }
        }
    }

    programmeDetails?.let { programme ->
        AlertDialog(
            onDismissRequest = { programmeDetails = null },
            title = { Text(programme.title) },
            text = {
                Column {
                    Text(Format.timeRange(programme.start, programme.stop))
                    programme.description?.let { desc ->
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val channel = detailsChannel
                    programmeDetails = null
                    if (channel != null) {
                        vm.playFromProgramme(channel, programme)
                        navController.navigate(Routes.PLAYER)
                    }
                }) { Text(stringResource(R.string.watch)) }
            },
            dismissButton = {
                TextButton(onClick = { programmeDetails = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuideRow(
    channel: ChannelEntity,
    programmes: List<ProgrammeEntity>,
    dayStart: Long,
    pxPerMin: Float,
    nameCellWidth: Dp,
    dayWidthDp: Dp,
    nowSec: Long,
    onProgrammeClick: (ProgrammeEntity) -> Unit,
) {
    val density = LocalDensity.current
    Row(Modifier.fillMaxWidth().height(56.dp)) {
        Text(
            text = channel.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(nameCellWidth)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Box(
            Modifier
                .width(dayWidthDp)
                .fillMaxHeight(),
        ) {
            programmes.forEach { p ->
                val startMin = ((p.start - dayStart) / 60f).coerceAtLeast(0f)
                val stopMin = ((p.stop - dayStart) / 60f).coerceAtMost(24f * 60f)
                if (stopMin <= startMin) return@forEach
                val x = with(density) { (startMin * pxPerMin).toDp() }
                val w = with(density) { ((stopMin - startMin) * pxPerMin).toDp() }
                val isCurrent = p.start <= nowSec && p.stop > nowSec
                val isPast = p.stop <= nowSec
                Surface(
                    onClick = { onProgrammeClick(p) },
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        isCurrent -> MaterialTheme.colorScheme.primaryContainer
                        isPast -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .offset(x = x)
                        .width(w - 2.dp)
                        .height(52.dp)
                        .padding(vertical = 1.dp),
                ) {
                    Column(
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Text(
                            text = p.title,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = Format.timeRange(p.start, p.stop),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
