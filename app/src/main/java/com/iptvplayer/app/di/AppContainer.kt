package com.iptvplayer.app.di

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.iptvplayer.app.data.db.AppDatabase
import com.iptvplayer.app.data.remote.Http
import com.iptvplayer.app.data.repo.EpgRepository
import com.iptvplayer.app.data.repo.PlaylistRepository
import com.iptvplayer.app.data.repo.ProgressRepository
import com.iptvplayer.app.data.repo.SettingsRepository
import com.iptvplayer.app.data.repo.UpdateRepository
import com.iptvplayer.app.parental.ParentalManager
import com.iptvplayer.app.player.PlaybackQueue
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Manual dependency container. Kept intentionally simple (no DI framework)
 * so that the project is easy to read and to continue by any developer.
 * Access it anywhere via [ServiceLocator.instance].
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val http: OkHttpClient by lazy { Http.defaultClient() }

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    val db: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "iptv.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val playlists: PlaylistRepository by lazy { PlaylistRepository(db, http, json) }
    val epg: EpgRepository by lazy { EpgRepository(db, http, json, playlists) }
    val progress: ProgressRepository by lazy { ProgressRepository(db) }
    val updates: UpdateRepository by lazy { UpdateRepository(appContext, http, json, settings) }
    val backup: BackupManager by lazy { BackupManager(db, json) }
    val parental: ParentalManager by lazy { ParentalManager(settings) }
    val playbackQueue: PlaybackQueue by lazy { PlaybackQueue() }
}

/** Application-wide service locator. Initialized in [com.iptvplayer.app.IPTVApp]. */
object ServiceLocator {

    @Volatile
    private var container: AppContainer? = null

    fun init(application: Application) {
        if (container == null) {
            synchronized(this) {
                if (container == null) {
                    container = AppContainer(application)
                }
            }
        }
    }

    val instance: AppContainer
        get() = checkNotNull(container) { "ServiceLocator is not initialized yet" }
}
