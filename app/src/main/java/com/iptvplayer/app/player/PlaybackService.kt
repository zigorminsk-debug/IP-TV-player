package com.iptvplayer.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.cancel
import com.iptvplayer.app.MainActivity
import com.iptvplayer.app.data.remote.Http
import com.iptvplayer.app.di.ServiceLocator
import kotlinx.coroutines.guava.future

/**
 * Foreground playback service. Owns the ExoPlayer instance and a Media3
 * session; the UI connects through a [androidx.media3.session.MediaController]
 * (see PlayerViewModel). Keeps playing in background and shows the standard
 * media notification.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var httpFactory: OkHttpDataSource.Factory
    private val serviceScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Main.immediate +
                kotlinx.coroutines.SupervisorJob(),
        )

    override fun onCreate() {
        super.onCreate()

        httpFactory = OkHttpDataSource.Factory(ServiceLocator.instance.http)
            .setUserAgent(Http.USER_AGENT)
        val dataSourceFactory = IptvDataSourceFactory(this, httpFactory)

        val player = ExoPlayer.Builder(this)
            .setTrackSelector(DefaultTrackSelector(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(MediaSessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private inner class MediaSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()

        /**
         * MediaItems sent from the UI carry play metadata (title, mime type,
         * per-channel HTTP headers) in RequestMetadata extras; resolve them
         * into fully playable items here.
         */
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = serviceScope.future {
            mediaItems.map { resolve(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolve(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri ?: return item
        val extras = item.requestMetadata.extras

        // The factory is shared by the service. Clear properties first so a
        // Referer/User-Agent from the previous channel cannot leak here.
        val headers = extras?.getSerializable(MediaItems.EXTRA_HEADERS)
        val map = if (headers is Map<*, *>) {
            headers.entries.associate { (k, v) -> k.toString() to v.toString() }
        } else {
            emptyMap()
        }
        httpFactory.setDefaultRequestProperties(map)

        val mime = extras?.getString(MediaItems.EXTRA_MIME) ?: MediaItems.guessMime(uri.toString())

        // Title/metadata are already attached to the item by the UI.
        val builder = item.buildUpon().setUri(uri)
        if (mime != null) builder.setMimeType(mime)
        return builder.build()
    }
}
