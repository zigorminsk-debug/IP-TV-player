package com.iptvplayer.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.iptvplayer.app.di.ServiceLocator
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic background refresh of playlists (whose auto-refresh interval has
 * elapsed) and EPG sources. Scheduled by [RefreshScheduler] from app settings.
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = ServiceLocator.instance
        return try {
            container.playlists.refreshAllDue()
            // EPG follows the same global interval (min 1h by worker contract).
            val hours = container.settings.settings.first().autoRefreshHours
            if (hours > 0) {
                container.epg.refreshAllDue(maxAgeMs = hours * 3_600_000L)
            }
            Result.success()
        } catch (e: Exception) {
            // Never crash the periodic job; it will try again next period.
            Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "iptv-auto-refresh"
    }
}

object RefreshScheduler {

    /** Schedules (hours > 0) or cancels (hours <= 0) the periodic refresh. */
    fun schedule(context: Context, hours: Int) {
        val wm = WorkManager.getInstance(context)
        if (hours <= 0) {
            wm.cancelUniqueWork(RefreshWorker.UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        wm.enqueueUniquePeriodicWork(
            RefreshWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
