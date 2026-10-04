package com.iptvplayer.app.ui.screens.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptvplayer.app.R
import com.iptvplayer.app.player.ExternalPlayer
import com.iptvplayer.app.player.PlayerViewModel
import com.iptvplayer.app.player.TrackSelections
import com.iptvplayer.app.ui.components.tvFocusable
import com.iptvplayer.app.util.Format
import kotlinx.coroutines.delay

private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT,
    AspectRatioFrameLayout.RESIZE_MODE_FILL,
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
)

private val RESIZE_LABELS = listOf(
    R.string.aspect_fit,
    R.string.aspect_fill,
    R.string.aspect_zoom,
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel()
    vm.connect()
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }

    val ui by vm.ui.collectAsState()
    val controller by vm.controller.collectAsState()
    val queueState by vm.queue.state.collectAsState()
    val programmes by vm.programmes.collectAsState()
    val sleepUntil by vm.sleepUntil.collectAsState()
    val sleepDone by vm.sleepDone.collectAsState()
    val castAvailable by vm.castAvailable.collectAsState()
    val casting by vm.casting.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var zapOpen by remember { mutableStateOf(false) }
    var trackDialogType by remember { mutableStateOf<Int?>(null) }
    var sleepMenuOpen by remember { mutableStateOf(false) }
    var aspectIndex by remember { mutableStateOf(0) }
    var sliderValue by remember { mutableStateOf(0f) }
    var sliderDragging by remember { mutableStateOf(false) }
    var inPip by remember { mutableStateOf(false) }

    val current = queueState?.channels?.getOrNull(queueState?.index ?: 0)

    LaunchedEffect(Unit) { vm.initCast() }

    // Keep screen on while playing.
    DisposableEffect(ui.playing) {
        view.keepScreenOn = ui.playing
        onDispose { view.keepScreenOn = false }
    }

    // PiP mode tracking.
    DisposableEffect(activity) {
        val act = activity as? androidx.activity.ComponentActivity
        if (act != null && android.os.Build.VERSION.SDK_INT >= 26) {
            val listener =
                androidx.core.util.Consumer<android.app.PictureInPictureModeChangedInfo> { info ->
                    inPip = info.isInPictureInPictureMode
                }
            act.addOnPictureInPictureModeChangedListener(listener)
            onDispose { act.removeOnPictureInPictureModeChangedListener(listener) }
        } else {
            onDispose { }
        }
    }

    // Auto-hide controls.
    LaunchedEffect(controlsVisible, ui.playing, zapOpen) {
        if (controlsVisible && ui.playing && !zapOpen) {
            delay(5000)
            controlsVisible = false
        }
    }

    // Immersive mode.
    DisposableEffect(controlsVisible, zapOpen, inPip) {
        val window = activity?.window
        if (window != null) {
            val controllerCompat =
                WindowCompat.getInsetsController(window, view)
            if (controlsVisible || zapOpen) {
                controllerCompat.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controllerCompat.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controllerCompat.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    BackHandler {
        when {
            zapOpen -> zapOpen = false
            !controlsVisible -> controlsVisible = true
            else -> onBack()
        }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && activity != null) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            activity.enterPictureInPictureMode(params)
        }
    }

    // Sync slider with position while not dragging.
    LaunchedEffect(ui.positionMs, ui.durationMs, sliderDragging) {
        if (!sliderDragging && ui.durationMs > 0) {
            sliderValue = (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        vm.zapPrevious(); true
                    }
                    Key.DirectionDown -> {
                        vm.zapNext(); true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        controlsVisible = !controlsVisible; true
                    }
                    Key.DirectionLeft -> {
                        controller?.seekBack(); true
                    }
                    Key.DirectionRight -> {
                        controller?.seekForward(); true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { playerView: PlayerView ->
                if (controller != null && playerView.player !== controller) {
                    playerView.player = controller
                }
                playerView.resizeMode = RESIZE_MODES[aspectIndex]
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (ui.buffering && ui.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        // ---- error overlay
        if (ui.error != null && !inPip) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.playback_error, ui.error ?: ""),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row {
                        TextButton(onClick = { vm.retry() }) {
                            Text(stringResource(R.string.retry))
                        }
                        current?.let { ch ->
                            TextButton(onClick = { ExternalPlayer.play(context, ch.url) }) {
                                Text(stringResource(R.string.play_external))
                            }
                        }
                        TextButton(onClick = { vm.clearError() }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }

        // ---- controls overlay
        AnimatedVisibility(
            visible = controlsVisible && !inPip && ui.error == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = Color.White,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = current?.name ?: "",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val nowSec = System.currentTimeMillis() / 1000
                            val nowProgramme = programmes.lastOrNull {
                                it.start <= nowSec && it.stop > nowSec
                            }
                            if (nowProgramme != null) {
                                Text(
                                    text = "${Format.time(nowProgramme.start)}  ${nowProgramme.title}",
                                    color = Color.White.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (castAvailable) {
                            IconButton(onClick = {
                                if (casting) vm.stopCasting() else vm.castCurrent()
                            }) {
                                Icon(
                                    Icons.Filled.Cast,
                                    contentDescription = stringResource(R.string.cast),
                                    tint = if (casting) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.White
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // catch-up quick actions
                    val nowSec = System.currentTimeMillis() / 1000
                    val nowProgramme = programmes.lastOrNull {
                        it.start <= nowSec && it.stop > nowSec
                    }
                    if (current?.catchupAvailable == true && nowProgramme != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Replay,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            TextButton(onClick = {
                                vm.playCatchup(nowProgramme.start, nowSec)
                            }) {
                                Text(stringResource(R.string.watch_from_start))
                            }
                            listOf(30L, 60L, 120L).forEach { back ->
                                TextButton(onClick = {
                                    vm.playCatchup(nowSec - back * 60, nowSec)
                                }) {
                                    Text(
                                        stringResource(
                                            R.string.minutes_back_fmt,
                                            back.toInt(),
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    // seek bar for VOD / archive
                    if (ui.durationMs > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = Format.msToClock(ui.positionMs),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = sliderValue,
                                onValueChange = {
                                    sliderDragging = true
                                    sliderValue = it
                                },
                                onValueChangeFinished = {
                                    sliderDragging = false
                                    vm.seekTo((sliderValue * ui.durationMs).toLong())
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            )
                            Text(
                                text = Format.msToClock(ui.durationMs),
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    // main transport controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { vm.zapPrevious() }) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = stringResource(R.string.prev_channel),
                                tint = Color.White,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        FilledIconButton(
                            onClick = { vm.togglePlayPause() },
                            modifier = Modifier.size(64.dp),
                        ) {
                            Icon(
                                imageVector = if (ui.playing) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.play_pause),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = { vm.zapNext() }) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = stringResource(R.string.next_channel),
                                tint = Color.White,
                            )
                        }
                    }

                    // secondary controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        IconButton(onClick = { trackDialogType = C.TRACK_TYPE_VIDEO }) {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = stringResource(R.string.video_tracks),
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { trackDialogType = C.TRACK_TYPE_AUDIO }) {
                            Icon(
                                Icons.Filled.Audiotrack,
                                contentDescription = stringResource(R.string.audio_tracks),
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { trackDialogType = C.TRACK_TYPE_TEXT }) {
                            Icon(
                                Icons.Filled.Subtitles,
                                contentDescription = stringResource(R.string.subtitle_tracks),
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = {
                            aspectIndex = (aspectIndex + 1) % RESIZE_MODES.size
                        }) {
                            Icon(
                                Icons.Filled.AspectRatio,
                                contentDescription = stringResource(RESIZE_LABELS[aspectIndex]),
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { sleepMenuOpen = true }) {
                            Icon(
                                Icons.Filled.Timer,
                                contentDescription = stringResource(R.string.sleep_timer),
                                tint = if (sleepUntil > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White
                                },
                            )
                        }
                        current?.let { ch ->
                            IconButton(onClick = { ExternalPlayer.play(context, ch.url) }) {
                                Icon(
                                    Icons.Filled.OpenInNew,
                                    contentDescription = stringResource(R.string.play_external),
                                    tint = Color.White,
                                )
                            }
                        }
                        if (Build.VERSION.SDK_INT >= 26 && activity != null) {
                            IconButton(onClick = { enterPip() }) {
                                Icon(
                                    Icons.Filled.PictureInPictureAlt,
                                    contentDescription = stringResource(R.string.pip),
                                    tint = Color.White,
                                )
                            }
                        }
                        IconButton(onClick = { zapOpen = true }) {
                            Icon(
                                Icons.Filled.ViewList,
                                contentDescription = stringResource(R.string.channel_list),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
        }

        // ---- zap drawer
        AnimatedVisibility(
            visible = zapOpen,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
            ) {
                Text(
                    text = stringResource(R.string.channel_list),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                LazyColumn {
                    val channels = queueState?.channels ?: emptyList()
                    itemsIndexed(channels, key = { _, ch -> ch.uid }) { index, ch ->
                        val selected = index == queueState?.index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusable()
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = "${ch.num}".takeIf { it != "0" }?.let { "$it. " } ?: "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = ch.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- track selection dialog
    trackDialogType?.let { type ->
        val groups = controller?.let { TrackSelections.buildGroups(it) } ?: emptyList()
        val group = groups.firstOrNull { it.trackType == type }
        AlertDialog(
            onDismissRequest = { trackDialogType = null },
            title = { Text(group?.title ?: stringResource(R.string.info)) },
            text = {
                Column {
                    if (group == null || group.options.isEmpty()) {
                        Text(stringResource(R.string.no_tracks))
                    } else {
                        group.options.forEach { option ->
                            TextButton(
                                onClick = {
                                    controller?.let(option.apply)
                                    trackDialogType = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = (if (option.isSelected) "✓  " else "      ") + option.label,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { trackDialogType = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    // ---- sleep timer menu
    if (sleepMenuOpen) {
        AlertDialog(
            onDismissRequest = { sleepMenuOpen = false },
            title = { Text(stringResource(R.string.sleep_timer)) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.setSleepTimer(0); sleepMenuOpen = false }) {
                            Text(stringResource(R.string.off))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60).forEach { min ->
                            TextButton(
                                onClick = { vm.setSleepTimer(min); sleepMenuOpen = false },
                            ) {
                                Text(stringResource(R.string.minutes_fmt, min))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(90, 120).forEach { min ->
                            TextButton(
                                onClick = { vm.setSleepTimer(min); sleepMenuOpen = false },
                            ) {
                                Text(stringResource(R.string.minutes_fmt, min))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (sleepDone) {
        AlertDialog(
            onDismissRequest = { vm.dismissSleepDone() },
            title = { Text(stringResource(R.string.sleep_timer)) },
            text = { Text(stringResource(R.string.sleep_timer_done)) },
            confirmButton = {
                TextButton(onClick = { vm.dismissSleepDone(); onBack() }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

/** Walks up the context chain to find the hosting Activity. */
fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
