/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.model.SourceKind
import app.opentv.player.StreamlinkResolver
import app.opentv.player.YouTubeChannelUrl
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * A foreground service that captures live streams to storage. Recording has to keep going when the
 * user backs out of the player, opens another app, or the screen sleeps — a foreground service
 * with a wake lock is what keeps the process (and the socket) alive to do that.
 *
 * Each recording is one coroutine copying bytes from the stream to the chosen [RecordingStorage]
 * sink. Stopping cancels the coroutine and the HTTP call so a blocking read unblocks at once.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Uncaught recording service task failure", error)
        },
    )
    private val captures = ConcurrentHashMap<Long, Capture>()
    // MP4 conversions running after their capture finished. They keep the service (and its
    // foreground notification) alive until the remux is done, so a long recording is not
    // interrupted the moment the socket closes.
    private val remuxes = ConcurrentHashMap<Long, Job>()
    private var wakeLock: PowerManager.WakeLock? = null

    private class Capture(
        @Volatile var call: Call?,
        val job: Job,
        @Volatile var streamlinkReader: StreamlinkResolver.RecordingReader? = null,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                if (id > 0 && !captures.containsKey(id)) startCapture(id)
            }
            ACTION_STOP -> {
                val id = intent.getLongExtra(EXTRA_ID, -1L)
                stopCapture(id)
            }
            ACTION_STOP_ALL -> captures.keys.toList().forEach { stopCapture(it) }
        }
        if (captures.isEmpty() && remuxes.isEmpty() && intent?.action != ACTION_START) stopSelfSafely()
        return START_NOT_STICKY
    }

    private fun startCapture(id: Long) {
        acquireWakeLock()
        // LAZY: register the capture before it can possibly finish, so a stream that fails
        // instantly cannot race `finish()` into stopping the service with a phantom capture.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { runCapture(id) }
        captures[id] = Capture(null, job)
        updateNotification()
        job.start()
    }

    private suspend fun runCapture(id: Long) {
        var setupRepo: app.opentv.data.repo.RecordingRepository? = null
        val setup = try {
            val graph = ServiceLocator.get(applicationContext)
            val repo = graph.recordingRepository
            setupRepo = repo
            val recording = repo.byId(id) ?: run {
                finish(id)
                return
            }
            Triple(graph, repo, recording)
        } catch (t: Throwable) {
            val error = t.message ?: t.javaClass.simpleName
            if (t is kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) {
                    runCatching {
                        setupRepo?.markFinished(
                            ok = false,
                            id = id,
                            atMillis = System.currentTimeMillis(),
                            bytes = 0L,
                            error = getString(R.string.rec_error_nothing),
                        )
                    }
                    finish(id)
                }
                throw t
            }
            Log.w(TAG, "Recording $id failed during setup", t)
            withContext(NonCancellable) {
                runCatching {
                    setupRepo?.markFinished(
                        ok = false,
                        id = id,
                        atMillis = System.currentTimeMillis(),
                        bytes = 0L,
                        error = error,
                    )
                }
                finish(id)
            }
            return
        }
        val graph = setup.first
        val repo = setup.second
        val recording = setup.third

        var total = 0L
        var error: String? = null
        var sink: RecordingStorage.Sink? = null
        var outputLocator = recording.filePath
        var lastReport = 0L
        try {
            val source = graph.sourceRepository.byId(recording.sourceId)
            val captureUrl = if (source?.kind == SourceKind.STALKER) {
                val channel = graph.catalogRepository.channel(recording.channelId)
                    ?: throw IOException(getString(R.string.rec_error_stalker_unavailable))
                val resolved = graph.catalogRepository.resolvePlaybackUrl(channel, source)
                if (resolved.isBlank() || resolved == channel.streamUrl) {
                    throw IOException(getString(R.string.rec_error_stalker_unavailable))
                }
                resolved
            } else {
                recording.streamUrl
            }
            repo.markStarted(id, System.currentTimeMillis())
            // Do not create the visible video file until a real media chunk has arrived. If the
            // process dies while a provider is connecting or returning an empty response, no
            // zero-byte recording is left behind.
            suspend fun writeChunk(bytes: ByteArray, offset: Int, length: Int) {
                var newlyOpened = false
                val destination = sink ?: RecordingStorage.openSink(
                    applicationContext,
                    graph.settings,
                    recording.filePath,
                ).also { opened ->
                    sink = opened
                    outputLocator = opened.resolvedLocator ?: recording.filePath
                    newlyOpened = true
                }
                try {
                    destination.output.write(bytes, offset, length)
                    if (newlyOpened) {
                        destination.output.flush()
                        (destination.output as? FileOutputStream)?.fd?.sync()
                    }
                } catch (writeError: IOException) {
                    throw RecordingOutputException(writeError)
                }
                total += length
                if (newlyOpened) {
                    destination.resolvedLocator?.let { actual ->
                        outputLocator = actual
                        if (actual != recording.filePath) repo.setFilePath(id, actual)
                    }
                    // Announce only after the first media bytes exist, so a tail watcher never
                    // receives a path to an empty file.
                    RecordingLiveState.markActive(id, outputLocator)
                }
                if (lastReport == 0L || total - lastReport >= SIZE_REPORT_BYTES) {
                    lastReport = total
                    repo.setSize(id, total)
                }
            }

            val scheduledEnd = recording.scheduledEndMillis
            val youtubeLiveUrl = YouTubeChannelUrl.asLiveUrlOrNull(captureUrl)
            if (youtubeLiveUrl != null) {
                total = copyStreamlinkRecording(id, youtubeLiveUrl, scheduledEnd) { chunk ->
                    writeChunk(chunk, 0, chunk.size)
                }
            } else {
                val buffer = ByteArray(BUFFER_BYTES)
                var deadReconnects = 0
                var lastNetworkError: String? = null

                // Direct IPTV `.ts` endpoints often close a rolling socket and expect reconnects.
                while (kotlin.coroutines.coroutineContext[Job]?.isActive == true) {
                    if (scheduledEnd > 0 && System.currentTimeMillis() >= scheduledEnd) break

                    val request = Request.Builder()
                        .url(captureUrl)
                        .header("User-Agent", recording.userAgent)
                        .build()
                    val call = graph.streamingHttpClient.newCall(request)
                    captures[id]?.call = call

                    val bytesThisLeg = try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                            val input = response.body?.byteStream() ?: throw java.io.IOException("Empty response")
                            var leg = 0L
                            while (kotlin.coroutines.coroutineContext[Job]?.isActive == true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                writeChunk(buffer, 0, read)
                                leg += read
                                if (scheduledEnd > 0 && System.currentTimeMillis() >= scheduledEnd) break
                            }
                            leg
                        }
                    } catch (t: kotlinx.coroutines.CancellationException) {
                        throw t
                    } catch (t: java.io.IOException) {
                        if (t is RecordingOutputException) throw t
                        lastNetworkError = t.message
                        Log.i(TAG, "Recording $id connection dropped (${t.message}); reconnecting")
                        0L
                    }

                    if (bytesThisLeg <= 0L) {
                        if (++deadReconnects >= MAX_DEAD_RECONNECTS) {
                            if (total == 0L) {
                                error = lastNetworkError?.takeIf(String::isNotBlank)
                                    ?: getString(R.string.rec_error_nothing)
                            }
                            break
                        }
                        kotlinx.coroutines.delay(RECONNECT_BACKOFF_MILLIS)
                    } else {
                        deadReconnects = 0
                        lastNetworkError = null
                    }
                }
            }
        } catch (t: Throwable) {
            // A cancelled stop is a clean finish, not an error.
            if (t is kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Recording $id stopped by user")
            } else {
                error = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Recording $id failed", t)
            }
        } finally {
            // The file is now at its final size — anyone watching it should see the true end.
            RecordingLiveState.markInactive(id)
            runCatching { sink?.close() }
            if (sink != null && total == 0L) {
                Log.i(TAG, "Recording $id received no media bytes; removing the empty destination")
                runCatching { RecordingStorage.delete(applicationContext, graph.settings, outputLocator) }
            }
            val ok = total > 0 && error == null
            runCatching {
                repo.markFinished(
                    id = id,
                    ok = ok,
                    atMillis = System.currentTimeMillis(),
                    bytes = total,
                    error = if (ok) null else (error ?: getString(R.string.rec_error_nothing)),
                )
            }
            if (ok && graph.settings.recordingContainer.value == AppSettings.RecordingContainer.MP4) {
                startRemux(id)
            }
            finish(id)
        }
    }

    /**
     * Convert a just-finished capture to MP4 without holding the capture map open. The original TS
     * stays in place until the conversion succeeds, so a failure simply keeps the TS recording.
     */
    private fun startRemux(id: Long) {
        if (remuxes.containsKey(id)) return
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val graph = ServiceLocator.get(applicationContext)
                val recording = graph.recordingRepository.byId(id)
                if (recording != null) {
                    val ok = RecordingRemux.convert(applicationContext, recording)
                    Log.i(TAG, if (ok) "Recording $id remuxed to MP4" else "Recording $id kept as TS")
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "Recording $id MP4 conversion failed", t)
            } finally {
                remuxes.remove(id)
                maybeStop()
            }
        }
        remuxes[id] = job
        updateNotification()
        job.start()
    }

    /** Streamlink reads transport bytes from its HLS reader; it cannot record the `.m3u8` URL itself. */
    private suspend fun copyStreamlinkRecording(
        id: Long,
        liveUrl: String,
        scheduledEndMillis: Long,
        writeChunk: suspend (ByteArray) -> Unit,
    ): Long {
        var total = 0L
        var deadReconnects = 0
        val resolver = ServiceLocator.get(applicationContext).streamlinkResolver

        while (kotlin.coroutines.coroutineContext[Job]?.isActive == true) {
            if (scheduledEndMillis > 0 && System.currentTimeMillis() >= scheduledEndMillis) break
            val reader = try {
                resolver.openRecording(liveUrl)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.i(TAG, "Recording $id could not open the YouTube live (${error.message}); retrying")
                if (++deadReconnects >= MAX_DEAD_RECONNECTS) break
                kotlinx.coroutines.delay(RECONNECT_BACKOFF_MILLIS)
                continue
            }

            captures[id]?.streamlinkReader = reader
            var bytesThisLeg = 0L
            try {
                while (kotlin.coroutines.coroutineContext[Job]?.isActive == true) {
                    if (scheduledEndMillis > 0 && System.currentTimeMillis() >= scheduledEndMillis) break
                    val chunk = reader.read(BUFFER_BYTES)
                    if (chunk.isEmpty()) break
                    writeChunk(chunk)
                    total += chunk.size
                    bytesThisLeg += chunk.size
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is RecordingOutputException) throw error
                Log.i(TAG, "Recording $id Streamlink reader ended (${error.message}); reconnecting")
            } finally {
                captures[id]?.streamlinkReader = null
                runCatching { reader.close() }
            }

            if (bytesThisLeg <= 0L) {
                if (++deadReconnects >= MAX_DEAD_RECONNECTS) break
                kotlinx.coroutines.delay(RECONNECT_BACKOFF_MILLIS)
            } else {
                deadReconnects = 0
            }
        }
        return total
    }

    private fun stopCapture(id: Long) {
        val capture = captures[id] ?: return
        runCatching { capture.call?.cancel() }
        capture.streamlinkReader?.let { reader -> scope.launch { runCatching { reader.close() } } }
        capture.job.cancel()
    }

    /** Called from the capture coroutine's finally to retire it and, if it was the last, stop. */
    private fun finish(id: Long) {
        captures.remove(id)
        maybeStop()
    }

    /** Retire the service only once nothing is capturing *and* no MP4 conversion is still running. */
    private fun maybeStop() {
        if (captures.isEmpty() && remuxes.isEmpty()) {
            stopSelfSafely()
        } else {
            updateNotification()
        }
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    /**
     * The user swiped OpenTV out of recents. That must NOT kill an in-progress recording — the
     * whole point of a foreground capture is that it keeps going when you leave the app. So while
     * any capture is still running we deliberately do not [stopSelf]; we re-assert foreground so
     * the OS keeps the process (and its wake lock) alive, and let each capture retire the service
     * itself when it finishes. Only when nothing is recording do we tidy up and stop.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (captures.isEmpty() && remuxes.isEmpty()) {
            stopSelfSafely()
            return
        }
        Log.i(TAG, "Task removed with ${captures.size} recording(s) and ${remuxes.size} conversion(s) — keeping alive")
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(captures.size)) }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- Wake lock ---------------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenTV:recording").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    // ---- Notification ------------------------------------------------------------------------

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(captures.size))
    }

    private fun buildNotification(count: Int): Notification {
        val text = when {
            count == 0 && remuxes.isNotEmpty() -> getString(R.string.rec_notification_converting)
            count <= 1 -> getString(R.string.rec_notification_one)
            else -> getString(R.string.rec_notification_many, count)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FullTV")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_fulltv_logo)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.rec_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = getString(R.string.rec_channel_desc) },
                )
            }
        }
    }

    companion object {
        private const val TAG = "OpenTV"
        private const val CHANNEL_ID = "recordings"
        private const val NOTIFICATION_ID = 4210
        private const val BUFFER_BYTES = 64 * 1024
        private const val SIZE_REPORT_BYTES = 1L * 1024 * 1024
        private const val MAX_WAKE_MILLIS = 6L * 60 * 60 * 1000 // 6h safety cap
        // Providers close a live .ts socket every ~30s; reconnect and keep appending. Give up only
        // after this many consecutive reconnects deliver nothing (the stream is genuinely gone).
        private const val MAX_DEAD_RECONNECTS = 5
        private const val RECONNECT_BACKOFF_MILLIS = 1500L

        private const val ACTION_START = "app.opentv.recording.START"
        private const val ACTION_STOP = "app.opentv.recording.STOP"
        private const val ACTION_STOP_ALL = "app.opentv.recording.STOP_ALL"
        private const val EXTRA_ID = "recordingId"

        fun start(context: Context, recordingId: Long) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, recordingId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context, recordingId: Long) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_ID, recordingId)
            }
            context.startService(intent)
        }
    }
}

private class RecordingOutputException(cause: IOException) :
    IOException(cause.message ?: "The recording destination could not be written.", cause)
