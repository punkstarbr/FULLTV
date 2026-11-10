/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.opentv.core.ServiceLocator
import app.opentv.core.VodRefreshPolicy
import app.opentv.data.repo.CatalogRepository
import java.util.concurrent.TimeUnit

/**
 * Background refresh of live channels and the guide. Large VOD catalogues are fetched only by
 * explicit/manual refreshes when stale or when the user opens Movies/Shows.
 *
 * Runs on WorkManager rather than a foreground timer so it survives the app being killed,
 * which on a TV box happens constantly. Failures return [Result.retry] with WorkManager's
 * exponential backoff — the app never hammers a provider that is already struggling.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = ServiceLocator.get(applicationContext)
        val now = System.currentTimeMillis()
        val force = inputData.getBoolean(KEY_FORCE, false)
        val sources = graph.sourceRepository.enabled()
        if (sources.isEmpty()) return Result.success()

        var anyFailed = false
        // A periodic worker must never download a potentially huge VOD catalogue just because
        // the device is online. Manual refresh can fetch it when stale; opening Movies/Shows
        // also performs the same TTL check on demand.
        val refreshVod = force && VodRefreshPolicy.shouldRefresh(graph.settings.vodSyncedAtMillis, now)
        var attemptedVodRefresh = false

        for (source in sources) {
            val result = if (graph.settings.liveEnabled.value) {
                graph.catalogRepository.syncLive(source, now)
            } else {
                CatalogRepository.SyncResult.Success(0, 0, 0)
            }
            when (result) {
                is CatalogRepository.SyncResult.Success ->
                    Log.i(TAG, "Catalogue for source ${source.id}: ${result.channelCount} channels")
                is CatalogRepository.SyncResult.Failed -> {
                    anyFailed = true
                    Log.w(TAG, "Catalogue sync failed for source ${source.id}")
                }
            }
            if (result is CatalogRepository.SyncResult.Success &&
                source.kind == app.opentv.data.model.SourceKind.XTREAM && refreshVod
            ) {
                graph.catalogRepository.syncVod(source, now)
                attemptedVodRefresh = true
            }
        }
        if (attemptedVodRefresh) graph.settings.vodSyncedAtMillis = now

        // Guides sync as one pass across every enabled feed — provider guides, built-in
        // free sources and user URLs merge into a single guide, then the matcher runs.
        val summary = graph.epgRepository.syncAll(now, force = force)
        if (summary.feedsFailed > 0) anyFailed = true
        Log.i(
            TAG,
            "Guide: ${summary.programmesWritten} programmes from ${summary.feedsSucceeded} " +
                "feed(s), ${summary.channelsMatched}/${summary.channelsTotal} channels matched",
        )

        // Book any newly-revealed series-link airings from this fresh guide.
        runCatching { graph.recordingEngine.rescanSeriesRules() }

        // A partial failure is still a retry — but the user keeps everything already on disk.
        return if (anyFailed) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "opentv-periodic-sync-v2"
        private const val LEGACY_WORK_NAME = "opentv-periodic-sync"
        private const val ONE_SHOT_WORK_NAME = "opentv-manual-sync"

        /**
         * Kick a single catalogue + guide refresh now, off the UI. Runs the same [doWork] as the
         * periodic job — so it honours the content-type toggles — but as one-time work, which means
         * it survives the user navigating away from the screen that triggered it (unlike a refresh
         * tied to a screen's ViewModel scope). REPLACE so repeated taps coalesce into one run.
         */
        fun refreshNow(context: Context, force: Boolean = true) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(Data.Builder().putBoolean(KEY_FORCE, force).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun schedule(context: Context, forceReschedule: Boolean = false) {
            val settings = app.opentv.core.AppSettings.get(context)
            val workManager = WorkManager.getInstance(context)
            if (!settings.catalogAutoRefresh.value) {
                workManager.cancelUniqueWork(WORK_NAME)
                workManager.cancelUniqueWork(LEGACY_WORK_NAME)
                return
            }
            val intervalHours = settings.catalogRefreshIntervalHours.value.toLong()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalHours,
                TimeUnit.HOURS,
            )
                .setInitialDelay(intervalHours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.MINUTES,
                )
                .build()

            // Migrate old requests once, and don't reset the new periodic timer on every app start.
            // The initial delay prevents an automatic catalogue download from competing with startup.
            workManager.cancelUniqueWork(LEGACY_WORK_NAME)
            if (forceReschedule) workManager.cancelUniqueWork(WORK_NAME)
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        private const val KEY_FORCE = "force_refresh"
    }
}
