package com.iptvplayer.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-configured playlist (M3U url or Xtream Codes account). */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // PlaylistType.name: M3U | XTREAM
    val url: String, // M3U url or Xtream server base url
    val username: String? = null,
    val password: String? = null,
    val preferHls: Boolean = true, // Xtream: use .m3u8 instead of .ts for live
    val refreshHours: Int = 0, // 0 = manual refresh only
    val lastUpdate: Long = 0,
    val orderNum: Int = 0,
)

/** A category (group) inside a playlist. */
@Entity(tableName = "categories", indices = [Index("playlistId")])
data class CategoryEntity(
    @PrimaryKey val uid: String, // "$playlistId:$kind:$remoteId"
    val playlistId: Long,
    val kind: String, // ChannelKind.name
    val remoteId: String,
    val name: String,
    val orderNum: Int,
    val isLocked: Boolean = false,
    val isHidden: Boolean = false,
)

/** A channel / VOD item / series entry. */
@Entity(
    tableName = "channels",
    indices = [Index("playlistId"), Index(value = ["playlistId", "kind"])],
)
data class ChannelEntity(
    @PrimaryKey val uid: String, // "$playlistId:$kind:$remoteId"
    val playlistId: Long,
    val kind: String, // ChannelKind.name
    val remoteId: String,
    val categoryId: String? = null, // CategoryEntity.uid
    val groupTitle: String? = null, // original #EXTINF group-title (M3U)
    val name: String,
    val url: String,
    val logo: String? = null,
    val tvgId: String? = null, // M3U tvg-id
    val epgChannelId: String? = null, // Xtream epg_channel_id
    val num: Int = 0,
    val orderNum: Int = 0,
    val language: String? = null,
    val containerExt: String? = null, // VOD / episode container extension
    val seriesId: Long? = null, // Xtream series id when kind == SERIES
    val plot: String? = null,
    val rating: Double? = null,
    val releaseDate: String? = null,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isLocked: Boolean = false,
    // Catch-up (archive) support
    val catchupMode: String? = null, // default | append | flussonic | shift
    val catchupSource: String? = null, // url template with {utc} etc.
    val catchupAvailable: Boolean = false,
    val archiveDurationMin: Int = 0,
    // Optional per-channel HTTP options from #EXTVLCOPT
    val httpUserAgent: String? = null,
    val httpReferrer: String? = null,
)

/** XMLTV EPG source; belongs to a playlist or is global. */
@Entity(tableName = "epg_sources", indices = [Index("playlistId")])
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long? = null, // null = global source
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val lastUpdate: Long = 0,
)

/** Channel mapping table of the last parsed XMLTV file(s). */
@Entity(tableName = "epg_channels")
data class EpgChannelEntity(
    @PrimaryKey val epgId: String,
    val displayName: String,
    val icon: String? = null,
)

/** A single EPG programme (times in epoch seconds). */
@Entity(tableName = "epg_programmes", indices = [Index("epgId", "start")])
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val icon: String? = null,
)

/** Resume position for VOD / series episodes. */
@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)
