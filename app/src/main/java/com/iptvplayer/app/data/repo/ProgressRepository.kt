package com.iptvplayer.app.data.repo

import com.iptvplayer.app.data.db.AppDatabase
import com.iptvplayer.app.data.db.WatchProgressEntity

/** Stores resume positions for VOD / series episodes. */
class ProgressRepository(private val db: AppDatabase) {

    suspend fun get(itemId: String): WatchProgressEntity? =
        db.progressDao().get(itemId)

    suspend fun save(itemId: String, positionMs: Long, durationMs: Long) {
        db.progressDao().upsert(
            WatchProgressEntity(
                itemId = itemId,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun clear() = db.progressDao().clear()
}
