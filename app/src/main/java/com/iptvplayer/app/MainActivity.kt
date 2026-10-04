package com.iptvplayer.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.iptvplayer.app.di.ServiceLocator
import com.iptvplayer.app.ui.AppRoot
import com.iptvplayer.app.ui.theme.IPTVTheme

/**
 * Single-activity app. All screens are Compose destinations inside
 * [AppRoot]; playback is hosted by PlaybackService.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by ServiceLocator.instance.settings.settings
                .collectAsState(initial = null)
            IPTVTheme(themeSetting = settings?.theme ?: "SYSTEM") {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (settings == null) {
                        Text(text = "")
                    } else {
                        AppRoot()
                    }
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.isInPip = isInPictureInPictureMode
    }
}

