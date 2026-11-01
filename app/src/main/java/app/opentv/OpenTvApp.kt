/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import android.util.Log
import app.opentv.core.LocaleUtils
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.work.SyncWorker
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OpenTvApp : Application(), ImageLoaderFactory {
    private val startupErrorHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Background startup task failed; continuing app startup", error)
    }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + startupErrorHandler)

    /**
     * The app-wide Coil loader, tuned for a poster-and-logo heavy UI on a low-end TV box. The
     * default loader keeps a small memory cache and no disk cache, so scrolling back through a
     * shelf — or reopening Movies — re-downloads and re-decodes every image. Here:
     *  - a bounded memory cache and a 32 MB disk cache mean art you've already seen paints from
     *    cache, instantly, instead of hitting the network;
     *  - no crossfade — an immediate swap reads as snappier on a d-pad grid than a fade, and skips
     *    a frame of blending per image;
     *  - RGB_565 for opaque art (posters/backdrops) halves the memory per bitmap, so more fits in
     *    cache; Coil keeps ARGB_8888 for anything with transparency, so channel logos are untouched.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.10)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .allowRgb565(true)
            .build()

    // So notifications and any app-context resources use the chosen language too, not just the UI.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleUtils.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        val graph = ServiceLocator.get(this)
        runCatching { SyncWorker.schedule(this) }
            .onFailure { Log.e(TAG, "Could not schedule background refresh", it) }
        // A killed MP4 conversion can leave its local input/output pair behind. These are only
        // staging files; completed recordings live in the configured recording target.
        File(cacheDir, "remux").listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > 60 * 60 * 1000L) {
                runCatching { file.delete() }
            }
        }

        // When the normaliser has moved on since the catalogue was last processed, re-clean
        // the stored channels and re-run the guide matcher — locally, no re-download. This
        // is why a name-cleanup fix shows up on the next launch rather than the next 6-hour
        // sync, and it costs nothing when the version has not changed.
        // Cold-start reconciliation: a recording still marked "in progress" at process start is an
        // orphan from a killed capture. Done here, once per process — NOT when the Recordings screen
        // opens — so checking on a recording you just started never marks it interrupted.
        appScope.launch { runCatching { graph.recordingRepository.failInterrupted() } }

        // Re-arm scheduled recordings on every launch, for the same reason as the reminders below:
        // a force-stop or app update drops their exact alarms and only a reboot is covered by the
        // boot receiver. Re-setting the same alarm is idempotent, so this quietly keeps bookings alive.
        appScope.launch { runCatching { graph.recordingEngine.rearmScheduled() } }

        // Re-arm programme reminders on every launch. Alarms are lost on a force-stop or app
        // update (not just a reboot, which the boot receiver already covers), and re-setting an
        // exact alarm for the same reminder is idempotent — so this quietly keeps bells alive.
        appScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                graph.reminderRepository.deleteEndedBefore(now)
                graph.reminderRepository.upcoming(now).forEach {
                    app.opentv.reminders.ReminderScheduler.set(this@OpenTvApp, it.id, it.startUtcMillis)
                }
            }
        }

        // Free, server-less "cloud" sync through the user's own NAS, if they've opted in. Writes
        // this device's bundle to the shared folder and merges in every other device's — favourites,
        // watch history and NAS recordings. Fire-and-forget and fully guarded: a blank or unreachable
        // NAS returns a result rather than throwing, so a bad launch never costs the user anything.
        if (graph.settings.nasAutoSync.value) {
            appScope.launch { runCatching { app.opentv.sync.NasSync(graph).sync() } }
        }

        appScope.launch {
            val prefs = getSharedPreferences("opentv", MODE_PRIVATE)
            val seen = prefs.getInt("normalizer_version", 0)
            if (seen < CatalogRepository.NORMALIZER_VERSION) {
                runCatching { graph.catalogRepository.renormalizeAll() }
                    .onSuccess {
                        prefs.edit().putInt("normalizer_version", CatalogRepository.NORMALIZER_VERSION).apply()
                    }
                    .onFailure { Log.w(TAG, "Channel normalization will retry later", it) }
            }
        }

        appScope.launch {
            // Runs off the UI thread. An EPG/network/database error is logged and must never
            // terminate the foreground app process.
            val now = System.currentTimeMillis()
            runCatching { graph.epgRepository.syncAll(now, force = false) }
                .onFailure { Log.w(TAG, "EPG startup refresh will retry later", it) }
            runCatching {
                graph.database.programmes().deleteOutsideWindow(
                    now - app.opentv.data.repo.EpgRepository.RETENTION_PAST_MILLIS,
                    now + graph.epgRepository.retentionFutureMillis,
                )
            }.onFailure { Log.w(TAG, "Could not prune expired EPG rows", it) }
        }
    }

    private companion object {
        const val TAG = "OpenTvApp"
    }
}
