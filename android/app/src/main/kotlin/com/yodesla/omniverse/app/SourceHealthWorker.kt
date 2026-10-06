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
 * Task 105: the weekly source health check (PLAN.md §6.1 style background work).
 *
 * Once a week, with network available, it asks every source the viewer added for its own account
 * state (expiry date, active or expired, connection slots), records when that source last synced
 * successfully, and compares today's Movies/Shows/Channels counts with the counts from the previous
 * week. What it finds lands in the source-health store, which is what Settings' warning chips and the
 * Home alert banner read.
 *
 * It only reads: no catalog sync, no streams, so it never spends one of a provider's playback slots.
 * The store's own seven-day rule decides whether a run is due, so a device that was off for a week
 * catches up on the next window instead of piling up runs.
 */
class SourceHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as OmniverseApp).graph
        return try {
            graph.checkSourceHealth()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing here should escape (the probe records per-source failures itself); retry later.
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "omniverse-weekly-source-health"

        /** Idempotent: KEEP leaves an existing weekly schedule alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SourceHealthWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
