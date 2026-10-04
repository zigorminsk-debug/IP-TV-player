package com.iptvplayer.app.player

import com.iptvplayer.app.data.db.ChannelEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the current playback queue (a list of channels) and the position in
 * it. Screens write into it before navigating to the player; PlayerViewModel
 * observes it and (re)starts playback on every change.
 */
class PlaybackQueue {

    data class State(
        val channels: List<ChannelEntity>,
        val index: Int,
        val startMs: Long = 0,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun set(channels: List<ChannelEntity>, index: Int, startMs: Long = 0) {
        if (channels.isEmpty()) {
            _state.value = null
            return
        }
        _state.value = State(channels, index.coerceIn(0, channels.lastIndex), startMs)
    }

    /** Jumps to [index] (zap) and starts playback from [startMs]. */
    fun seekTo(index: Int, startMs: Long = 0) {
        val s = _state.value ?: return
        if (index !in s.channels.indices) return
        _state.value = State(s.channels, index, startMs)
    }

    fun next() {
        val s = _state.value ?: return
        if (s.index < s.channels.lastIndex) seekTo(s.index + 1)
    }

    fun previous() {
        val s = _state.value ?: return
        if (s.index > 0) seekTo(s.index - 1)
    }

    val current: ChannelEntity?
        get() = _state.value?.let { it.channels.getOrNull(it.index) }
}
