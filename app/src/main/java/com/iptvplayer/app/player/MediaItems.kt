package com.iptvplayer.app.player

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.iptvplayer.app.data.model.PlayRequest

/** Builds/derives [MediaItem]s and mime types. */
object MediaItems {

    const val EXTRA_TITLE = "com.iptvplayer.app.TITLE"
    const val EXTRA_MIME = "com.iptvplayer.app.MIME"
    const val EXTRA_HEADERS = "com.iptvplayer.app.HEADERS"

    fun build(request: PlayRequest): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_TITLE, request.title)
            request.mimeType?.let { putString(EXTRA_MIME, it) }
            if (request.headers.isNotEmpty()) {
                putSerializable(EXTRA_HEADERS, HashMap(request.headers))
            }
        }
        return MediaItem.Builder()
            .setUri(request.url)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title)
                    .setArtist(request.subtitle)
                    .build(),
            )
            .build()
    }

    /** Guesses a mime type from the url extension (null = let ExoPlayer infer). */
    fun guessMime(url: String): String? {
        val clean = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            clean.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            clean.endsWith(".ts") || clean.endsWith(".mpegts") -> MimeTypes.VIDEO_MP2T
            clean.endsWith(".mp4") || clean.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            clean.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            clean.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            clean.endsWith(".avi") -> "video/avi"
            clean.endsWith(".flv") -> "video/x-flv"
            clean.endsWith(".mov") -> "video/quicktime"
            clean.endsWith(".wmv") -> "video/x-ms-wmv"
            clean.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
            clean.endsWith(".m4a") -> MimeTypes.AUDIO_MP4
            clean.endsWith(".aac") -> MimeTypes.AUDIO_AAC
            else -> null
        }
    }
}
