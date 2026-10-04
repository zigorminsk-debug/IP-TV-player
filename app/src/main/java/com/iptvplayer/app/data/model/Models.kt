package com.iptvplayer.app.data.model

/** Playlist source type. */
enum class PlaylistType { M3U, XTREAM }

/** Content kind of a channel row / category. */
enum class ChannelKind { LIVE, MOVIE, SERIES }

/** Result of a playlist/EPG refresh operation. */
sealed class RefreshResult {
    data class Success(val channels: Int, val categories: Int) : RefreshResult()
    data class Error(val message: String?) : RefreshResult()

    val isSuccess: Boolean get() = this is Success
}

/** Channel list sort orders. */
enum class ChannelSort { ORDER, NAME_ASC, NAME_DESC }

/** Simple "now / next" EPG pair for a channel. */
data class NowNext(
    val now: ProgrammeInfo?,
    val next: ProgrammeInfo?,
)

data class ProgrammeInfo(
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String? = null,
)

/** A resolved, playable item handed over to the player. */
data class PlayRequest(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val startPositionMs: Long = 0L,
    val isLive: Boolean = true,
    val itemId: String,
)
