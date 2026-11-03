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
import app.opentv.recording.RecordingStorage
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
        Log.e(TAG, "Background startup task failed (${error.javaClass.simpleName}); continuing app startup")
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
            .okHttpClient(ServiceLocator.get(this).httpClient)
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
        val processStartedAtMillis = System.currentTimeMillis()
        val graph = ServiceLocator.get(this)
        runCatching { SyncWorker.schedule(this) }
            .onFailure { Log.e(TAG, "Could not schedule background refresh (${it.javaClass.simpleName})") }
        appScope.launch {
            runCatching {
                File(filesDir, "ai-models/whisper").deleteRecursively()
                cacheDir.listFiles()
                    ?.filter { it.name.startsWith("fulltv-caption-") && it.extension == "wav" }
                    ?.forEach { it.delete() }
            }.onFailure { Log.i(TAG, "Legacy speech-caption data cleanup failed (${it.javaClass.simpleName})") }
        }
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
        // Cold-start reconciliation: fail only captures whose start predates this process. A
        // scheduled alarm may start a new capture while this asynchronous check is still running.
        // Done once per process, not when the Recordings screen opens.
        appScope.launch {
            val interrupted = runCatching {
                graph.recordingRepository.active().filter { it.startedAtMillis < processStartedAtMillis }
            }
                .onFailure { Log.w(TAG, "Could not inspect interrupted recordings (${it.javaClass.simpleName})") }
                .getOrDefault(emptyList())
            runCatching { graph.recordingRepository.failInterruptedBefore(processStartedAtMillis) }
                .onFailure { Log.w(TAG, "Could not mark interrupted recordings (${it.javaClass.simpleName})") }
            interrupted.forEach { recording ->
                val resolvedUsbLocator = runCatching {
                    RecordingStorage.recoverUsbPlaceholderLocator(
                        this@OpenTvApp,
                        graph.settings,
                        recording.filePath,
                    )
                }.onFailure {
                    Log.w(TAG, "Could not resolve interrupted USB recording ${recording.id} (${it.javaClass.simpleName})")
                }.getOrNull()
                val locatorPersisted = resolvedUsbLocator?.let { resolved ->
                    runCatching { graph.recordingRepository.setFilePath(recording.id, resolved) }
                        .onFailure {
                            Log.w(TAG, "Could not persist interrupted USB locator ${recording.id} (${it.javaClass.simpleName})")
                        }
                        .isSuccess
                } ?: true
                val effectiveLocator = if (locatorPersisted) resolvedUsbLocator ?: recording.filePath else recording.filePath
                val recoveredFtpBytes = runCatching {
                    RecordingStorage.recoverInterrupted(this@OpenTvApp, graph.settings, effectiveLocator)
                }.onFailure {
                    Log.w(TAG, "Could not recover interrupted FTP recording ${recording.id} (${it.javaClass.simpleName})")
                }.getOrDefault(-1L)
                // Keep any non-empty capture as playable; only remove known-empty outputs and
                // unresolved USB placeholders. A SAF document created just before death is first
                // repointed from its temporary placeholder to the actual content:// URI.
                val size = recoveredFtpBytes.takeIf { it > 0L }
                    ?: RecordingStorage.sizeOf(this@OpenTvApp, graph.settings, effectiveLocator)
                val orphanPlaceholder = RecordingStorage.isUsbPlaceholder(effectiveLocator)
                if (size > 0L && locatorPersisted) {
                    runCatching {
                        graph.recordingRepository.markFinished(
                            id = recording.id,
                            ok = true,
                            atMillis = System.currentTimeMillis(),
                            bytes = size,
                            error = null,
                        )
                    }.onFailure {
                        Log.w(TAG, "Could not publish recovered recording ${recording.id} (${it.javaClass.simpleName})")
                    }
                } else if (locatorPersisted && (orphanPlaceholder || size == 0L)) {
                    val removed = runCatching {
                        RecordingStorage.delete(this@OpenTvApp, graph.settings, effectiveLocator)
                    }.getOrDefault(false)
                    if (!removed) {
                        Log.w(TAG, "Could not clean interrupted recording destination ${recording.id}")
                    }
                }
            }
        }

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
                    .onFailure { Log.w(TAG, "Channel normalization will retry later (${it.javaClass.simpleName})") }
            }
        }

        appScope.launch {
            // Runs off the UI thread. An EPG/network/database error is logged and must never
            // terminate the foreground app process.
            val now = System.currentTimeMillis()
            runCatching { graph.epgRepository.syncAll(now, force = false) }
                .onFailure { Log.w(TAG, "EPG startup refresh will retry later (${it.javaClass.simpleName})") }
            runCatching {
                graph.database.programmes().deleteOutsideWindow(
                    now - app.opentv.data.repo.EpgRepository.RETENTION_PAST_MILLIS,
                    now + graph.epgRepository.retentionFutureMillis,
                )
            }.onFailure { Log.w(TAG, "Could not prune expired EPG rows (${it.javaClass.simpleName})") }
        }
    }

    private companion object {
        const val TAG = "OpenTvApp"
    }
}
