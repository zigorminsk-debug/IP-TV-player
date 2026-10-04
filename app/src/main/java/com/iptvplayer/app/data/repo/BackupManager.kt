package com.iptvplayer.app.data.repo

import com.iptvplayer.app.data.db.AppDatabase
import com.iptvplayer.app.data.db.EpgSourceEntity
import com.iptvplayer.app.data.db.PlaylistEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Export/import of app configuration (playlists + EPG sources) as JSON.
 * Used by Settings → «Экспорт/Импорт». Credentials are included in the file,
 * so users should treat it as sensitive.
 */
class BackupManager(
    private val db: AppDatabase,
    private val json: Json,
) {

    @Serializable
    data class BackupPlaylist(
        val name: String,
        val type: String,
        val url: String,
        val username: String? = null,
        val password: String? = null,
        val preferHls: Boolean = true,
        val refreshHours: Int = 0,
    )

    @Serializable
    data class BackupEpgSource(
        val name: String,
        val url: String,
        val playlistName: String?,
    )

    @Serializable
    data class Backup(
        val format: Int = FORMAT_VERSION,
        val exportedAt: Long,
        val appVersion: String,
        val playlists: List<BackupPlaylist>,
        val epgSources: List<BackupEpgSource>,
    )

    suspend fun export(): String {
        val playlists = db.playlistDao().getAll()
        val sources = db.epgDao().allSources()
        val backup = Backup(
            exportedAt = System.currentTimeMillis(),
            appVersion = com.iptvplayer.app.BuildConfig.VERSION_NAME,
            playlists = playlists.map {
                BackupPlaylist(
                    name = it.name,
                    type = it.type,
                    url = it.url,
                    username = it.username,
                    password = it.password,
                    preferHls = it.preferHls,
                    refreshHours = it.refreshHours,
                )
            },
            epgSources = sources.map { s ->
                val plName = s.playlistId?.let { id -> playlists.firstOrNull { it.id == id }?.name }
                BackupEpgSource(s.name, s.url, plName)
            },
        )
        return json.encodeToString(Backup.serializer(), backup)
    }

    /** @return number of imported playlists. */
    suspend fun import(text: String): Int {
        val backup = json.decodeFromString(Backup.serializer(), text)
        val existing = db.playlistDao().getAll()
        val nameToId = mutableMapOf<String, Long>()
        var imported = 0
        for (p in backup.playlists) {
            val duplicate = existing.any { it.type == p.type && it.url == p.url }
            if (duplicate) {
                val match = existing.first { it.type == p.type && it.url == p.url }
                nameToId[p.name] = match.id
                continue
            }
            val order = db.playlistDao().nextOrder()
            val id = db.playlistDao().upsert(
                PlaylistEntity(
                    name = p.name,
                    type = p.type,
                    url = p.url,
                    username = p.username,
                    password = p.password,
                    preferHls = p.preferHls,
                    refreshHours = p.refreshHours,
                    orderNum = order,
                ),
            )
            nameToId[p.name] = id
            imported++
        }
        for (s in backup.epgSources) {
            val plId = s.playlistName?.let { nameToId[it] }
            val exists = db.epgDao().allSources().any { it.url == s.url }
            if (!exists) {
                db.epgDao().upsertSource(
                    EpgSourceEntity(playlistId = plId, name = s.name, url = s.url),
                )
            }
        }
        return imported
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MIME = "application/json"
    }
}
