package com.iptvplayer.app

import android.app.Application
import com.iptvplayer.app.di.ServiceLocator
import com.iptvplayer.app.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Initializes the manual DI container and re-arms
 * the periodic playlist/EPG refresh worker according to stored settings.
 */
class IPTVApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)

        // Keep the periodic background refresh in sync with the setting.
        val container = ServiceLocator.instance
        appScope.launch {
            container.settings.settings.collect { settings ->
                RefreshScheduler.schedule(this@IPTVApp, settings.autoRefreshHours)
            }
        }
    }
}
