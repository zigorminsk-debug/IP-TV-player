package com.iptvplayer.app.player

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.cast.framework.CastContext
import androidx.media3.cast.CastPlayer
import com.iptvplayer.app.data.db.ChannelEntity
import com.iptvplayer.app.data.db.ProgrammeEntity
import com.iptvplayer.app.data.model.PlayRequest
import com.iptvplayer.app.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Player state holder. Connects to [PlaybackService] via MediaController,
 * observes the [PlaybackQueue] and drives playback. Lives as long as the
 * activity, so playback continues in background after UI release.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val container = ServiceLocator.instance
    val queue = container.playbackQueue

    /** Aggregated UI state polled/derived from the MediaController. */
    data class UiState(
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val tracksRevision: Int = 0,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    /** Programmes of the current channel (for now/next + catch-up bar). */
    private val _programmes = MutableStateFlow<List<ProgrammeEntity>>(emptyList())
    val programmes: StateFlow<List<ProgrammeEntity>> = _programmes.asStateFlow()

    /** Sleep timer deadline (epoch ms); 0 = disabled. */
    private val _sleepUntil = MutableStateFlow(0L)
    val sleepUntil: StateFlow<Long> = _sleepUntil.asStateFlow()

    private val _sleepDone = MutableStateFlow(false)
    val sleepDone: StateFlow<Boolean> = _sleepDone.asStateFlow()

    // Chromecast (null when unavailable — no GMS etc.)
    val castAvailable = MutableStateFlow(false)
    val casting = MutableStateFlow(false)
    private var castPlayer: CastPlayer? = null
    private var castInitAttempted = false

    private var connectJob: Job? = null
    private var pollJob: Job? = null
    private var progressJob: Job? = null
    private var sleepJob: Job? = null
    private var lastPlayed: PlaybackQueue.State? = null
    private var savedItemId: String? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.value = _ui.value.copy(playing = isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            val duration = _controller.value?.duration ?: 0
            _ui.value = _ui.value.copy(
                buffering = buffering,
                durationMs = if (duration > 0) duration else _ui.value.durationMs,
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            _ui.value = _ui.value.copy(
                error = error.errorCodeName.removePrefix("ERROR_CODE_")
                    .replace('_', ' '),
            )
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            _ui.value = _ui.value.copy(tracksRevision = _ui.value.tracksRevision + 1)
        }
    }

    init {
        connect()
        // React to queue changes (channel selection / zapping).
        viewModelScope.launch {
            queue.state.collect { state ->
                if (state != null && state != lastPlayed) {
                    lastPlayed = state
                    playInternal(state)
                }
            }
        }
    }

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
                val c = MediaController.Builder(app, token).buildAsync().await()
                c.addListener(listener)
                applyPreferredLanguages(c)
                _controller.value = c
                startPolling()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message ?: "controller error")
            }
        }
    }

    private suspend fun applyPreferredLanguages(c: MediaController) {
        val settings = container.settings.settings.first()
        val builder = c.trackSelectionParameters.buildUpon()
        if (settings.preferredAudioLang.isNotBlank()) {
            builder.setPreferredAudioLanguage(settings.preferredAudioLang.trim())
        }
        if (settings.preferredSubsLang.isNotBlank()) {
            builder.setPreferredTextLanguage(settings.preferredSubsLang.trim())
        }
        c.trackSelectionParameters = builder.build()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                val c = _controller.value
                if (c != null) {
                    val duration = c.duration
                    _ui.value = _ui.value.copy(
                        positionMs = c.currentPosition,
                        durationMs = if (duration > 0) duration else 0,
                    )
                }
                delay(1000)
            }
        }
    }

    private suspend fun playInternal(state: PlaybackQueue.State) {
        val c = _controller.value ?: run {
            // wait for controller
            _controller.first { it != null }
        } ?: return
        val channel = state.channels.getOrNull(state.index) ?: return
        val isLive = channel.kind == "LIVE"
        val headers = buildMap {
            channel.httpUserAgent?.let { put("User-Agent", it) }
            channel.httpReferrer?.let { put("Referer", it) }
        }
        val request = PlayRequest(
            url = channel.url,
            title = channel.name,
            // Leave extension-less live URLs unspecified: Media3 will use the
            // response Content-Type. Forcing HLS breaks providers serving raw TS.
            mimeType = MediaItems.guessMime(channel.url),
            headers = headers,
            startPositionMs = state.startMs,
            isLive = isLive,
            itemId = channel.uid,
        )
        val item = MediaItems.build(request)
        _ui.value = UiState() // reset state
        c.setMediaItem(item, state.startMs)
        c.prepare()
        c.play()
        savedItemId = channel.uid
        startProgressSaving(channel)
        loadProgrammes(channel)
    }

    private fun startProgressSaving(channel: ChannelEntity) {
        progressJob?.cancel()
        if (channel.kind == "LIVE") return
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(5000)
                val c = _controller.value ?: continue
                val duration = c.duration
                if (duration > 0 && c.currentPosition > 10_000) {
                    container.progress.save(channel.uid, c.currentPosition, duration)
                }
            }
        }
    }

    private fun loadProgrammes(channel: ChannelEntity) {
        viewModelScope.launch {
            val playlist = container.playlists.playlist(channel.playlistId)
            _programmes.value = runCatching {
                container.epg.channelProgrammes(channel, playlist)
            }.getOrDefault(emptyList())
        }
    }

    // ------------------------------------------------------------ controls

    fun togglePlayPause() {
        val c = _controller.value ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun zapNext() = queue.next()

    fun zapPrevious() = queue.previous()

    fun playIndex(index: Int) = queue.seekTo(index)

    fun seekTo(positionMs: Long) {
        _controller.value?.seekTo(positionMs)
    }

    fun stopPlayback() {
        val c = _controller.value ?: return
        progressJob?.cancel()
        val id = savedItemId
        if (id != null) {
            val duration = c.duration
            if (duration > 0 && c.currentPosition > 10_000) {
                viewModelScope.launch {
                    container.progress.save(id, c.currentPosition, duration)
                }
            }
        }
        c.stop()
        c.clearMediaItems()
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun retry() {
        val state = lastPlayed ?: return
        val c = _controller.value ?: return
        viewModelScope.launch {
            playInternal(state.copy(startMs = 0))
        }
    }

    // ---------------------------------------------------------- catch-up

    /** Plays a catch-up window for the current channel. */
    fun playCatchup(startSec: Long, endSec: Long) {
        val channel = queue.current ?: return
        val url = Catchup.buildUrl(
            channel,
            startSec,
            endSec,
            System.currentTimeMillis() / 1000,
        ) ?: return
        viewModelScope.launch {
            val c = _controller.first { it != null } ?: return@launch
            val request = PlayRequest(
                url = url,
                title = channel.name,
                mimeType = MediaItems.guessMime(url),
                headers = buildMap {
                    channel.httpUserAgent?.let { put("User-Agent", it) }
                    channel.httpReferrer?.let { put("Referer", it) }
                },
                itemId = channel.uid,
                isLive = false,
            )
            _ui.value = UiState()
            c.setMediaItem(MediaItems.build(request))
            c.prepare()
            c.play()
        }
    }

    // -------------------------------------------------------- sleep timer

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepUntil.value = 0
            return
        }
        _sleepUntil.value = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            _controller.value?.pause()
            _sleepUntil.value = 0
            _sleepDone.value = true
        }
    }

    fun dismissSleepDone() {
        _sleepDone.value = false
    }

    // ----------------------------------------------------------- casting

    fun initCast() {
        if (castInitAttempted) return
        castInitAttempted = true
        try {
            val app = getApplication<Application>()
            CastContext.getSharedInstance(app, Runnable::run)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        try {
                            castPlayer = CastPlayer(task.result)
                            castAvailable.value = true
                        } catch (e: Exception) {
                            castAvailable.value = false
                        }
                    } else {
                        castAvailable.value = false
                    }
                }
        } catch (e: Exception) {
            castAvailable.value = false
        }
    }

    fun castCurrent() {
        val channel = queue.current ?: return
        val cp = castPlayer ?: return
        val item = MediaItem.Builder()
            .setUri(channel.url)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(channel.name)
                    .build(),
            )
            .build()
        cp.setMediaItem(item)
        cp.prepare()
        cp.play()
        casting.value = true
    }

    fun stopCasting() {
        castPlayer?.stop()
        casting.value = false
    }

    // ----------------------------------------------------------- teardown

    override fun onCleared() {
        val c = _controller.value
        val id = savedItemId
        if (c != null && id != null) {
            val duration = c.duration
            if (duration > 0 && c.currentPosition > 10_000) {
                // fire-and-forget save through app scope
                (getApplication<Application>().applicationContext as? com.iptvplayer.app.IPTVApp)
                    ?.appScope?.launch {
                        runCatching { container.progress.save(id, c.currentPosition, duration) }
                    }
            }
        }
        pollJob?.cancel()
        progressJob?.cancel()
        sleepJob?.cancel()
        castPlayer?.release()
        c?.removeListener(listener)
        c?.release()
        _controller.value = null
        super.onCleared()
    }
}
