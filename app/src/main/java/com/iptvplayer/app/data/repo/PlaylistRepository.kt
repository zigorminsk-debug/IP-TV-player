package com.iptvplayer.app.data.repo

import androidx.room.withTransaction
import com.iptvplayer.app.data.db.AppDatabase
import com.iptvplayer.app.data.db.CategoryEntity
import com.iptvplayer.app.data.db.ChannelEntity
import com.iptvplayer.app.data.db.EpgSourceEntity
import com.iptvplayer.app.data.db.PlaylistEntity
import com.iptvplayer.app.data.db.PlaylistCount
import com.iptvplayer.app.data.model.ChannelKind
import com.iptvplayer.app.data.model.PlaylistType
import com.iptvplayer.app.data.model.RefreshResult
import com.iptvplayer.app.data.remote.Http
import com.iptvplayer.app.data.remote.M3uParser
import com.iptvplayer.app.data.remote.XtreamClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * Manages playlists: CRUD, refresh (M3U / Xtream Codes), channel and category
 * queries, favorites/hide/lock flags. User flags survive refreshes because
 * entity uids are deterministic.
 */
class PlaylistRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    json: Json,
) {
    private val playlistDao = db.playlistDao()
    private val categoryDao = db.categoryDao()
    private val channelDao = db.channelDao()
    private val epgDao = db.epgDao()
    private val xc = XtreamClient(http, json)

    /** UI-facing refresh status per playlist id. */
    data class RefreshState(
        val running: Boolean = false,
        val error: String? = null,
        val finishedAt: Long = 0,
    )

    private val _refreshStates = MutableStateFlow<Map<Long, RefreshState>>(emptyMap())
    val refreshStates: StateFlow<Map<Long, RefreshState>> = _refreshStates

    // ------------------------------------------------------------ queries

    fun observePlaylists(): Flow<List<PlaylistEntity>> = playlistDao.observeAll()

    fun observeCounts(): Flow<List<PlaylistCount>> = channelDao.observeCounts()

    fun observeChannels(playlistId: Long, kind: ChannelKind): Flow<List<ChannelEntity>> =
        channelDao.observe(playlistId, kind.name)

    fun observeAllChannels(playlistId: Long): Flow<List<ChannelEntity>> =
        channelDao.observeAllForPlaylist(playlistId)

    suspend fun channelByEpgId(epgId: String): ChannelEntity? = channelDao.byEpgId(epgId)

    fun observeCategories(playlistId: Long, kind: ChannelKind): Flow<List<CategoryEntity>> =
        categoryDao.observe(playlistId, kind.name)

    fun observeAllCategories(playlistId: Long): Flow<List<CategoryEntity>> =
        categoryDao.observeAll(playlistId)

    suspend fun playlist(id: Long): PlaylistEntity? = playlistDao.getById(id)

    suspend fun channelByUid(uid: String): ChannelEntity? = channelDao.byUid(uid)

    suspend fun channelsForPlaylist(playlistId: Long): List<ChannelEntity> =
        channelDao.visibleForPlaylist(playlistId)

    suspend fun categoriesForPlaylist(playlistId: Long): List<CategoryEntity> =
        categoryDao.forPlaylist(playlistId)

    suspend fun searchChannels(query: String): List<ChannelEntity> {
        val q = query.trim()
        return if (q.isEmpty()) emptyList() else channelDao.searchByName(q, 300)
    }

    // -------------------------------------------------------------- CRUD

    data class NewPlaylist(
        val name: String,
        val type: PlaylistType,
        val url: String,
        val username: String = "",
        val password: String = "",
        val preferHls: Boolean = true,
        val refreshHours: Int = 0,
        val epgUrl: String = "",
    )

    /**
     * Creates a playlist (and an EPG source when applicable) and returns its id.
     * For Xtream an XMLTV source is auto-created; the user can disable it later.
     */
    suspend fun addPlaylist(p: NewPlaylist): Long {
        val order = playlistDao.nextOrder()
        val id = playlistDao.upsert(
            PlaylistEntity(
                name = p.name,
                type = p.type.name,
                url = p.url,
                username = p.username.takeIf { it.isNotBlank() },
                password = p.password.takeIf { it.isNotBlank() },
                preferHls = p.preferHls,
                refreshHours = p.refreshHours,
                orderNum = order,
            ),
        )
        if (p.type == PlaylistType.XTREAM) {
            val acc = XtreamClient.XcAccount(p.url, p.username, p.password)
            db.epgDao().upsertSource(
                EpgSourceEntity(
                    playlistId = id,
                    name = "XMLTV (${p.name})",
                    url = XtreamClient.xmltvUrl(acc),
                ),
            )
        } else if (p.epgUrl.isNotBlank()) {
            db.epgDao().upsertSource(
                EpgSourceEntity(playlistId = id, name = "EPG (${p.name})", url = p.epgUrl.trim()),
            )
        }
        return id
    }

    suspend fun updatePlaylist(entity: PlaylistEntity): Long = playlistDao.upsert(entity)

    suspend fun deletePlaylist(id: Long) {
        db.withTransaction {
            playlistDao.delete(id)
            channelDao.deleteForPlaylist(id)
            categoryDao.deleteForPlaylist(id)
            epgDao.deleteSourcesForPlaylist(id)
        }
    }

    /** Swaps a playlist with the previous/next one in the list. */
    suspend fun movePlaylist(id: Long, up: Boolean) {
        val all = playlistDao.getAll()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return
        val other = if (up) index - 1 else index + 1
        if (other !in all.indices) return
        val a = all[index]
        val b = all[other]
        playlistDao.upsert(a.copy(orderNum = b.orderNum))
        playlistDao.upsert(b.copy(orderNum = a.orderNum))
    }

    // ------------------------------------------------------------- flags

    suspend fun setFavorite(uid: String, favorite: Boolean) =
        channelDao.setFavorite(uid, favorite)

    suspend fun setChannelHidden(uid: String, hidden: Boolean) =
        channelDao.setHidden(uid, hidden)

    suspend fun setChannelLocked(uid: String, locked: Boolean) =
        channelDao.setLocked(uid, locked)

    suspend fun setCategoryLocked(uid: String, locked: Boolean) =
        categoryDao.setLocked(uid, locked)

    suspend fun setCategoryHidden(uid: String, hidden: Boolean) =
        categoryDao.setHidden(uid, hidden)

    // ----------------------------------------------------------- refresh

    suspend fun refresh(id: Long): RefreshResult {
        val pl = playlistDao.getById(id)
            ?: return RefreshResult.Error("Playlist not found")
        setRefresh(id, RefreshState(running = true))
        val result = try {
            when (runCatching { PlaylistType.valueOf(pl.type) }.getOrDefault(PlaylistType.M3U)) {
                PlaylistType.M3U -> refreshM3u(pl)
                PlaylistType.XTREAM -> refreshXtream(pl)
            }
        } catch (e: Exception) {
            RefreshResult.Error(e.message ?: e.javaClass.simpleName)
        }
        setRefresh(
            id,
            if (result is RefreshResult.Error) {
                RefreshState(running = false, error = result.message)
            } else {
                RefreshState(running = false, finishedAt = System.currentTimeMillis())
            },
        )
        return result
    }

    /** Refreshes all playlists whose auto-refresh interval has elapsed. */
    suspend fun refreshAllDue() {
        val now = System.currentTimeMillis()
        playlistDao.getAll()
            .filter {
                it.refreshHours > 0 && now - it.lastUpdate > it.refreshHours * 3_600_000L
            }
            .forEach { refresh(it.id) }
    }

    private fun setRefresh(id: Long, state: RefreshState) {
        _refreshStates.update { it + (id to state) }
    }

    private suspend fun refreshM3u(pl: PlaylistEntity): RefreshResult {
        val tmp = File.createTempFile("iptv-playlist", ".m3u")
        try {
            Http.downloadToFile(http, pl.url, tmp)
            val parsed = M3uParser.parse(tmp.inputStream())
            val oldChannels = channelDao.forPlaylist(pl.id).associateBy { it.uid }
            val oldCategories = categoryDao.forPlaylist(pl.id).associateBy { it.uid }

            // Collect groups in order of appearance.
            val groups = LinkedHashMap<String, Int>()
            for (e in parsed.entries) {
                val g = (e.extGrp ?: e.attrs["group-title"])?.trim().orEmpty()
                if (g.isNotEmpty() && !groups.containsKey(g)) groups[g] = groups.size
            }

            val categories = groups.map { (name, index) ->
                val uid = "${pl.id}:${ChannelKind.LIVE.name}:$name"
                val old = oldCategories[uid]
                CategoryEntity(
                    uid = uid,
                    playlistId = pl.id,
                    kind = ChannelKind.LIVE.name,
                    remoteId = name,
                    name = name,
                    orderNum = index,
                    isLocked = old?.isLocked ?: false,
                    isHidden = old?.isHidden ?: false,
                )
            }

            val channels = parsed.entries.mapIndexed { index, e ->
                val group = (e.extGrp ?: e.attrs["group-title"])?.trim()
                val remoteId = e.url
                val uid = "${pl.id}:${ChannelKind.LIVE.name}:$remoteId"
                val old = oldChannels[uid]
                val catchup = e.attrs["catchup"]?.lowercase()
                ChannelEntity(
                    uid = uid,
                    playlistId = pl.id,
                    kind = ChannelKind.LIVE.name,
                    remoteId = remoteId,
                    categoryId = group?.takeIf { it.isNotEmpty() }
                        ?.let { "${pl.id}:${ChannelKind.LIVE.name}:$it" },
                    groupTitle = group?.takeIf { it.isNotEmpty() },
                    name = e.name.ifBlank { "Channel ${index + 1}" },
                    url = e.url,
                    logo = e.attrs["tvg-logo"] ?: e.attrs["logo"],
                    tvgId = e.attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                    num = index + 1,
                    orderNum = index,
                    language = e.attrs["tvg-language"],
                    isFavorite = old?.isFavorite ?: false,
                    isHidden = old?.isHidden ?: false,
                    isLocked = old?.isLocked ?: false,
                    catchupMode = catchup,
                    catchupSource = e.attrs["catchup-source"],
                    catchupAvailable = catchup != null && catchup != "none",
                    httpUserAgent = e.httpUserAgent,
                    httpReferrer = e.httpReferrer,
                )
            }

            db.withTransaction {
                categoryDao.deleteForKind(pl.id, ChannelKind.LIVE.name)
                if (categories.isNotEmpty()) categoryDao.upsertAll(categories)
                channelDao.deleteForKind(pl.id, ChannelKind.LIVE.name)
                if (channels.isNotEmpty()) channelDao.upsertAll(channels)
                playlistDao.setLastUpdate(pl.id, System.currentTimeMillis())
            }

            // Auto-register EPG from the #EXTM3U header when no source exists.
            val headerEpg = parsed.headerAttrs["url-tvg"] ?: parsed.headerAttrs["x-tvg-url"]
            if (!headerEpg.isNullOrBlank() && epgDao.sourcesForPlaylist(pl.id).isEmpty()) {
                epgDao.upsertSource(
                    EpgSourceEntity(
                        playlistId = pl.id,
                        name = "EPG (${pl.name})",
                        url = headerEpg,
                    ),
                )
            }
            return RefreshResult.Success(channels.size, categories.size)
        } finally {
            tmp.delete()
        }
    }

    private suspend fun refreshXtream(pl: PlaylistEntity): RefreshResult {
        val account = XtreamClient.XcAccount(
            serverUrl = pl.url,
            username = pl.username.orEmpty(),
            password = pl.password.orEmpty(),
        )
        val user = xc.getUserInfo(account)
            ?: throw IOException("Xtream authentication failed (check server/user/password)")
        val acc = if (user.serverUrl.isNotBlank()) account.copy(serverUrl = user.serverUrl) else account

        val oldChannels = channelDao.forPlaylist(pl.id).associateBy { it.uid }
        val oldCategories = categoryDao.forPlaylist(pl.id).associateBy { it.uid }

        data class KindData(
            val kind: ChannelKind,
            val xcCategories: List<XtreamClient.XcCategory>,
            val streams: List<XtreamClient.XcStream>,
        )

        val live = KindData(
            ChannelKind.LIVE,
            xc.getCategories(acc, "get_live_categories"),
            xc.getStreams(acc, "get_live_streams"),
        )
        val vod = KindData(
            ChannelKind.MOVIE,
            xc.getCategories(acc, "get_vod_categories"),
            xc.getStreams(acc, "get_vod_streams"),
        )
        val series = KindData(
            ChannelKind.SERIES,
            xc.getCategories(acc, "get_series_categories"),
            xc.getStreams(acc, "get_series"),
        )

        val categories = mutableListOf<CategoryEntity>()
        val channels = mutableListOf<ChannelEntity>()

        for (kd in listOf(live, vod, series)) {
            kd.xcCategories.forEachIndexed { index, cat ->
                val uid = "${pl.id}:${kd.kind.name}:${cat.id}"
                val old = oldCategories[uid]
                categories += CategoryEntity(
                    uid = uid,
                    playlistId = pl.id,
                    kind = kd.kind.name,
                    remoteId = cat.id,
                    name = cat.name,
                    orderNum = index,
                    isLocked = old?.isLocked ?: false,
                    isHidden = old?.isHidden ?: false,
                )
            }
            kd.streams.forEachIndexed { index, s ->
                val remoteId = s.streamId.toString()
                val uid = "${pl.id}:${kd.kind.name}:$remoteId"
                val old = oldChannels[uid]
                val url = when (kd.kind) {
                    ChannelKind.LIVE -> XtreamClient.liveStreamUrl(acc, s.streamId, pl.preferHls)
                    ChannelKind.MOVIE -> XtreamClient.movieUrl(
                        acc,
                        s.streamId,
                        s.containerExt ?: "mp4",
                    )
                    ChannelKind.SERIES -> ""
                }
                channels += ChannelEntity(
                    uid = uid,
                    playlistId = pl.id,
                    kind = kd.kind.name,
                    remoteId = remoteId,
                    categoryId = s.categoryId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "${pl.id}:${kd.kind.name}:$it" },
                    name = s.name,
                    url = url,
                    logo = s.logo,
                    epgChannelId = s.epgChannelId?.takeIf { it.isNotBlank() },
                    num = index + 1,
                    orderNum = index,
                    containerExt = s.containerExt,
                    seriesId = if (kd.kind == ChannelKind.SERIES) s.streamId else null,
                    plot = s.plot,
                    rating = s.rating,
                    releaseDate = s.releaseDate,
                    isFavorite = old?.isFavorite ?: false,
                    isHidden = old?.isHidden ?: false,
                    isLocked = old?.isLocked ?: false,
                    catchupAvailable = if (kd.kind == ChannelKind.LIVE) s.archive else false,
                    archiveDurationMin = s.archiveDurationMin,
                )
            }
        }

        db.withTransaction {
            for (kd in listOf(live, vod, series)) {
                categoryDao.deleteForKind(pl.id, kd.kind.name)
                channelDao.deleteForKind(pl.id, kd.kind.name)
            }
            if (categories.isNotEmpty()) categoryDao.upsertAll(categories)
            if (channels.isNotEmpty()) channelDao.upsertAll(channels)
            playlistDao.setLastUpdate(pl.id, System.currentTimeMillis())
        }
        return RefreshResult.Success(channels.size, categories.size)
    }

    /** Xtream account for a playlist (used by VOD/series/EPG loaders). */
    suspend fun xcAccountFor(playlistId: Long): XtreamClient.XcAccount? {
        val pl = playlistDao.getById(playlistId) ?: return null
        if (pl.type != PlaylistType.XTREAM.name) return null
        return XtreamClient.XcAccount(
            serverUrl = pl.url,
            username = pl.username.orEmpty(),
            password = pl.password.orEmpty(),
        )
    }

    val xtreamClient: XtreamClient get() = xc
}
