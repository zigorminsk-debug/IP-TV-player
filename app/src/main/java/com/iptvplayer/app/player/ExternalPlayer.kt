package com.iptvplayer.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a stream url in an external player (VLC, MX Player, …). Useful for
 * protocols the built-in player cannot handle (UDP multicast, some RTMP…).
 */
object ExternalPlayer {

    fun play(context: Context, url: String) {
        val mime = MediaItems.guessMime(url) ?: "video/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, null)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
