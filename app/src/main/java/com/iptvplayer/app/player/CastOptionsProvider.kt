package com.iptvplayer.app.player

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Chromecast options: uses the default media receiver app, so no developer
 * account/app id is required. On devices without Google Play Services the
 * CastContext simply fails to initialize and casting is disabled.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
