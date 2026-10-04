package com.iptvplayer.app.data.repo

import com.iptvplayer.app.data.db.AppDatabase
import com.iptvplayer.app.data.db.ChannelEntity
import com.iptvplayer.app.data.db.EpgChannelEntity
import com.iptvplayer.app.data.db.EpgSourceEntity
import com.iptvplayer.app.data.db.PlaylistEntity
import com.iptvplayer.app.data.db.ProgrammeEntity
import com.iptvplayer.app.data.model.NowNext
import com.iptvplayer.app.data.model.PlaylistType
import com.iptvplayer.app.data.model.ProgrammeInfo
import com.iptvplayer.app.data.model.RefreshResult
import com.iptvplayer.app.data.remote.Http
import com.iptvplayer.app.data.remote.XmlTvParser
import com.iptvplayer.app.data.remote.XtreamClient
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

/**
 * EPG (electronic programme guide) repository:
 *  - downloads/parses XMLTV sources into a Room cache;
 *  - resolves channels to EPG ids (tvg-id / epg_channel_id / name fallback);
 *  - provides now/next and day-schedule queries;
 *  - loads short EPG from Xtream panels on demand.
 */
class EpgRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    json: Json,
    @Suppress("unused") private val playlists: PlaylistRepository,
) {

    private val epgDao = db.epgDao()
    private val xc by lazy {
        playlists.xtreamClient
    }

    // In-memory index: lowercase display-name -> epgId (name fallback matching).
    @Volatile
    private var nameIndex: Map<String, String>? = null

    // ------------------------------------------------------------- sources

    fun observeSources(): Flow<List<EpgSourceEntity>> = epgDao.observeSources()

    suspend fun addSource(name: String, url: String, playlistId: Long?) {
        epgDao.upsertSource(
            EpgSourceEntity(
                playlistId = playlistId,
                name = name.ifBlank { url },
                url = url.trim(),
            ),
        )
    }

    suspend fun deleteSource(id: Long) = epgDao.deleteSource(id)

    suspend fun setSourceEnabled(id: Long, enabled: Boolean) =
        epgDao.setSourceEnabled(id, enabled)

    suspend fun sourceById(id: Long): EpgSourceEntity? =
        epgDao.allSources().firstOrNull { it.id == id }

    suspend fun refreshSource(id: Long): RefreshResult {
        val source = sourceById(id) ?: return RefreshResult.Error("EPG source not found")
        val now = System.currentTimeMillis() / 1000
        val tmp = File.createTempFile("iptv-epg", ".xml")
        return try {
            Http.downloadToFile(http, source.url, tmp)
            val parsed = XmlTvParser.parse(
                tmp.inputStream(),
                keepFromSec = now - 48 * 3600,
                keepUntilSec = now + 8 * 24 * 3600,
            )
            val ids = parsed.channels.map { it.id }
            persistEpgData(parsed, ids)
            epgDao.setSourceLastUpdate(id, System.currentTimeMillis())
            nameIndex = null
            RefreshResult.Success(parsed.programmes.size, parsed.channels.size)
        } catch (e: Exception) {
            RefreshResult.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            tmp.delete()
        }
    }

    private suspend fun persistEpgData(parsed: XmlTvParser.Result, ids: List<String>) {
        db.withTransaction {
            epgDao.upsertChannels(
                parsed.channels.map {
                    EpgChannelEntity(it.id, it.names.firstOrNull() ?: it.id, it.icon)
                },
            )
            for (chunk in ids.chunked(900)) {
                epgDao.deleteFor(chunk)
            }
            val entities = parsed.programmes.map { p ->
                ProgrammeEntity(
                    epgId = p.channelId,
                    start = p.start,
                    stop = p.stop,
                    title = p.title,
                    description = p.desc,
                    category = p.category,
                    icon = p.icon,
                )
            }
            for (chunk in entities.chunked(500)) {
                epgDao.insertProgrammes(chunk)
            }
        }
    }

    suspend fun refreshAllDue(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        epgDao.allSources()
            .filter { it.enabled && now - it.lastUpdate > maxAgeMs }
            .forEach { refreshSource(it.id) }
    }

    suspend fun clearAllProgrammes() {
        epgDao.clearProgrammes()
        nameIndex = null
    }

    suspend fun programmeCount(): Int = epgDao.programmeCount()

    // -------------------------------------------------------- resolution

    private suspend fun index(): Map<String, String> =
        nameIndex ?: epgDao.allChannels()
            .associate { it.displayName.trim().lowercase() to it.epgId }
            .also { nameIndex = it }

    /** Canonical EPG id for a channel or null when not resolvable. */
    suspend fun epgIdFor(channel: ChannelEntity): String? {
        channel.tvgId?.let { return it }
        channel.epgChannelId?.let { return it }
        if (channel.kind != "LIVE") return null
        val idx = index()
        return idx[channel.name.trim().lowercase()]
    }

    // ------------------------------------------------------------ queries

    /** Now/next programme for each of [channels] (keyed by channel uid). */
    suspend fun nowNext(channels: List<ChannelEntity>): Map<String, NowNext> {
        if (channels.isEmpty()) return emptyMap()
        val idByUid = HashMap<String, String>(channels.size)
        for (ch in channels) {
            epgIdFor(ch)?.let { idByUid[ch.uid] = it }
        }
        if (idByUid.isEmpty()) return emptyMap()
        val now = System.currentTimeMillis() / 1000
        val rows = queryWindow(idByUid.values.distinct(), now - 12 * 3600, now + 72 * 3600)

        val byEpg = rows.groupBy { it.epgId }
        val result = HashMap<String, NowNext>(channels.size)
        for ((uid, epgId) in idByUid) {
            val list = byEpg[epgId].orEmpty()
            val current = list.lastOrNull { it.start <= now && it.stop > now }
            val next = list.firstOrNull { it.start > now }
            result[uid] = NowNext(
                now = current?.toInfo(),
                next = next?.toInfo(),
            )
        }
        return result
    }

    /**
     * Day schedule keyed by channel uid. [channels] should be the LIVE channels
     * of one playlist; window is [dayStart, dayEnd) in epoch seconds.
     */
    suspend fun daySchedule(
        channels: List<ChannelEntity>,
        dayStart: Long,
        dayEnd: Long,
    ): Map<String, List<ProgrammeEntity>> {
        if (channels.isEmpty()) return emptyMap()
        val idByUid = HashMap<String, String>(channels.size)
        for (ch in channels) {
            epgIdFor(ch)?.let { idByUid[ch.uid] = it }
        }
        if (idByUid.isEmpty()) return emptyMap()
        val rows = queryWindow(idByUid.values.distinct(), dayStart, dayEnd)
        val byEpg = rows.groupBy { it.epgId }
        val result = HashMap<String, List<ProgrammeEntity>>(channels.size)
        for (ch in channels) {
            val id = idByUid[ch.uid] ?: continue
            result[ch.uid] = byEpg[id].orEmpty().sortedBy { it.start }
        }
        return result
    }

    /**
     * Full programme list for a single channel. Falls back to the Xtream API
     * (get_simple_data_table) for Xtream channels without cached XMLTV data.
     */
    suspend fun channelProgrammes(
        channel: ChannelEntity,
        playlist: PlaylistEntity?,
    ): List<ProgrammeEntity> {
        val id = epgIdFor(channel)
        if (id != null) {
            val cached = epgDao.forChannel(id)
            val now = System.currentTimeMillis() / 1000
            if (cached.isNotEmpty() && cached.any { it.stop > now }) return cached
        }
        if (playlist != null && playlist.type == PlaylistType.XTREAM.name &&
            channel.kind == "LIVE" && channel.remoteId.toLongOrNull() != null
        ) {
            val fetched = fetchXtreamEpg(playlist, channel)
            if (fetched.isNotEmpty()) return fetched
        }
        return id?.let { epgDao.forChannel(it) } ?: emptyList()
    }

    /** Loads short EPG from the Xtream panel and caches it. */
    private suspend fun fetchXtreamEpg(
        playlist: PlaylistEntity,
        channel: ChannelEntity,
    ): List<ProgrammeEntity> {
        val account = XtreamClient.XcAccount(
            serverUrl = playlist.url,
            username = playlist.username.orEmpty(),
            password = playlist.password.orEmpty(),
        )
        val streamId = channel.remoteId.toLongOrNull() ?: return emptyList()
        val items = runCatching { xc.getStreamEpg(account, streamId) }.getOrDefault(emptyList())
        if (items.isEmpty()) return emptyList()
        val cacheId = channel.tvgId ?: channel.epgChannelId ?: "xc:${channel.remoteId}"
        val entities = items.map {
            ProgrammeEntity(
                epgId = cacheId,
                start = it.start,
                stop = it.stop,
                title = it.title,
                description = it.description,
            )
        }
        for (chunk in entities.chunked(500)) epgDao.insertProgrammes(chunk)
        epgDao.deleteFor(listOf(cacheId).let { ids ->
            // remove programmes that ended long ago
            ids
        })
        epgDao.deleteOlderThan(System.currentTimeMillis() / 1000 - 24 * 3600)
        nameIndex = null
        return entities.sortedBy { it.start }
    }

    suspend fun searchTitles(query: String): List<ProgrammeEntity> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return epgDao.searchTitles(q, System.currentTimeMillis() / 1000)
    }

    private suspend fun queryWindow(ids: List<String>, from: Long, until: Long): List<ProgrammeEntity> =
        ids.chunked(900).flatMap { epgDao.window(it, from, until) }

    private fun ProgrammeEntity.toInfo() = ProgrammeInfo(
        start = start,
        stop = stop,
        title = title,
        description = description,
    )
}
