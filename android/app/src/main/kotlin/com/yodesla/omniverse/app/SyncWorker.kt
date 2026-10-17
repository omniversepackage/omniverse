package com.yodesla.omniverse.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps channels, guide and catalog fresh without the app being opened (PLAN.md §6.1).
 * Checks the viewer's source/discovery schedules every 15 minutes. The engine's safety
 * rules apply (no removals from empty/collapsed lists), and one sync per source runs at a time.
 * No streams are opened, so it never uses a provider's playback connection slot.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as OmniverseApp).graph
        return try {
            graph.autoRefresh.run()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sync reports its own per-stage errors; anything escaping is unexpected: try later.
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "omniverse-auto-refresh-v2"

        /** Idempotent: KEEP leaves an existing schedule alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).cancelUniqueWork("omniverse-periodic-sync")
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
