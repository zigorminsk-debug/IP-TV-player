package com.iptvplayer.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY orderNum, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists ORDER BY orderNum, id")
    suspend fun getAll(): List<PlaylistEntity>

    @Upsert
    suspend fun upsert(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(orderNum), -1) + 1 FROM playlists")
    suspend fun nextOrder(): Int

    @Query("UPDATE playlists SET lastUpdate = :ts WHERE id = :id")
    suspend fun setLastUpdate(id: Long, ts: Long)
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId AND kind = :kind ORDER BY orderNum")
    fun observe(playlistId: Long, kind: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId ORDER BY kind, orderNum")
    fun observeAll(playlistId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: Long): List<CategoryEntity>

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun deleteForKind(playlistId: Long, kind: String)

    @Query("DELETE FROM categories WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("UPDATE categories SET isLocked = :locked WHERE uid = :uid")
    suspend fun setLocked(uid: String, locked: Boolean)

    @Query("UPDATE categories SET isHidden = :hidden WHERE uid = :uid")
    suspend fun setHidden(uid: String, hidden: Boolean)
}

@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND kind = :kind ORDER BY orderNum")
    fun observe(playlistId: Long, kind: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY kind, orderNum")
    fun observeAllForPlaylist(playlistId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE tvgId = :epgId OR epgChannelId = :epgId LIMIT 1")
    suspend fun byEpgId(epgId: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE uid = :uid")
    suspend fun byUid(uid: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId")
    suspend fun forPlaylist(playlistId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND isHidden = 0 ORDER BY orderNum")
    suspend fun visibleForPlaylist(playlistId: Long): List<ChannelEntity>

    @Upsert
    suspend fun upsertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun deleteForKind(playlistId: Long, kind: String)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("UPDATE channels SET isFavorite = :favorite WHERE uid = :uid")
    suspend fun setFavorite(uid: String, favorite: Boolean)

    @Query("UPDATE channels SET isHidden = :hidden WHERE uid = :uid")
    suspend fun setHidden(uid: String, hidden: Boolean)

    @Query("UPDATE channels SET isLocked = :locked WHERE uid = :uid")
    suspend fun setLocked(uid: String, locked: Boolean)

    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT :limit")
    suspend fun searchByName(query: String, limit: Int = 300): List<ChannelEntity>

    @Query("SELECT playlistId, COUNT(*) AS count FROM channels GROUP BY playlistId")
    fun observeCounts(): Flow<List<PlaylistCount>>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun totalCount(): Int
}

/** Helper projection for per-playlist channel counts. */
data class PlaylistCount(val playlistId: Long, val count: Int)

@Dao
interface EpgDao {

    // ---- EPG sources ------------------------------------------------------

    @Upsert
    suspend fun upsertSource(source: EpgSourceEntity): Long

    @Query("DELETE FROM epg_sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query("DELETE FROM epg_sources WHERE playlistId = :playlistId")
    suspend fun deleteSourcesForPlaylist(playlistId: Long)

    @Query("SELECT * FROM epg_sources ORDER BY name")
    fun observeSources(): Flow<List<EpgSourceEntity>>

    @Query("SELECT * FROM epg_sources")
    suspend fun allSources(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_sources WHERE playlistId = :playlistId")
    suspend fun sourcesForPlaylist(playlistId: Long): List<EpgSourceEntity>

    @Query("UPDATE epg_sources SET enabled = :enabled WHERE id = :id")
    suspend fun setSourceEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE epg_sources SET lastUpdate = :ts WHERE id = :id")
    suspend fun setSourceLastUpdate(id: Long, ts: Long)

    // ---- EPG data ---------------------------------------------------------

    @Upsert
    suspend fun upsertChannels(channels: List<EpgChannelEntity>)

    @Query("SELECT * FROM epg_channels")
    suspend fun allChannels(): List<EpgChannelEntity>

    @Query("SELECT * FROM epg_channels")
    fun observeChannels(): Flow<List<EpgChannelEntity>>

    @Insert
    suspend fun insertProgrammes(programmes: List<ProgrammeEntity>)

    @Query("DELETE FROM epg_programmes WHERE epgId IN (:ids)")
    suspend fun deleteFor(ids: List<String>)

    @Query("DELETE FROM epg_programmes WHERE stop < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM epg_programmes")
    suspend fun clearProgrammes()

    @Query("SELECT COUNT(*) FROM epg_programmes")
    suspend fun programmeCount(): Int

    /** Programmes overlapping the [from, until) window (epoch seconds). */
    @Query(
        "SELECT * FROM epg_programmes WHERE epgId IN (:ids) AND start < :until " +
            "AND stop > :from ORDER BY start",
    )
    suspend fun window(ids: List<String>, from: Long, until: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM epg_programmes WHERE epgId = :id ORDER BY start")
    suspend fun forChannel(id: String): List<ProgrammeEntity>

    @Query(
        "SELECT * FROM epg_programmes WHERE title LIKE '%' || :query || '%' " +
            "AND stop > :now ORDER BY start LIMIT 200",
    )
    suspend fun searchTitles(query: String, now: Long): List<ProgrammeEntity>
}

@Dao
interface ProgressDao {

    @Query("SELECT * FROM watch_progress WHERE itemId = :itemId")
    suspend fun get(itemId: String): WatchProgressEntity?

    @Upsert
    suspend fun upsert(progress: WatchProgressEntity)

    @Query("DELETE FROM watch_progress")
    suspend fun clear()
}
