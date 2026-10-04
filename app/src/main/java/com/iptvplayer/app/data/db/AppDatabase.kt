package com.iptvplayer.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        EpgSourceEntity::class,
        EpgChannelEntity::class,
        ProgrammeEntity::class,
        WatchProgressEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun epgDao(): EpgDao
    abstract fun progressDao(): ProgressDao
}
