/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Recording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Turns a finished MPEG-TS capture into an MP4 without re-encoding it.
 *
 * Live IPTV endpoints speak MPEG-TS, so the capture always starts as `.ts` — writing MP4 while the
 * stream is still growing would leave an index that can't be finalised. Once the capture ends this
 * pass copies the bytes to the cache, asks Media3 to transmux the existing video/audio samples into
 * an MP4 (no decode, no quality loss, quick even on a weak box), writes the result back to the
 * recording's destination, repoints the database row and only then removes the original TS.
 *
 * Any failure — an exotic codec, a full disk, a NAS that went away — leaves the original TS intact,
 * so recording never becomes worse because MP4 was requested.
 */
object RecordingRemux {

    private data class Tracks(val video: String, val audio: String?)

    /** @return true when the MP4 was written and the row now points at it. */
    @OptIn(UnstableApi::class)
    suspend fun convert(context: Context, recording: Recording): Boolean {
        val graph = ServiceLocator.get(context)
        val settings = graph.settings
        val cache = File(context.cacheDir, "remux").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val input = File(cache, "in-$stamp.ts")
        val output = File(cache, "out-$stamp.mp4")
        var targetLocator: String? = null
        fun rollbackTarget() {
            runCatching {
                rollbackRemuxTarget(targetLocator, recording.filePath) { locator ->
                    RecordingStorage.delete(context, settings, locator)
                }
            }
        }
        try {
            // An internal recording already is a local file; skip the cache copy entirely. USB
            // (content://) and NAS recordings are streamed into the cache first — that also gives
            // Media3 the local path it needs for the input.
            val localSource = recording.filePath.startsWith("/") &&
                File(recording.filePath).isFile
            val source = if (localSource) File(recording.filePath) else input
            if (!localSource) {
                withContext(Dispatchers.IO) {
                    FileOutputStream(input).use {
                        RecordingStorage.copyTo(context, settings, recording.filePath, it)
                    }
                }
            }
            val tracks = probe(source) ?: return false

            val started = recording.startedAtMillis
                .takeIf { it > 0 }
                ?: recording.scheduledStartMillis.takeIf { it > 0 }
                ?: stamp
            val name = RecordingStorage.fileNameFor(
                recording.channelName,
                recording.title,
                started,
                extension = "mp4",
            )
            val planned = RecordingStorage.plannedLocator(context, settings, name)
            // Internal output can be written straight to its final path — no second cache copy.
            val directOutput = planned.startsWith("/")
            val result = if (directOutput) File(planned).also { it.parentFile?.mkdirs() } else output
            if (directOutput) targetLocator = planned

            if (!transmux(context, source, result, tracks)) {
                if (directOutput) result.delete()
                return false
            }
            if (!localSource) input.delete()
            if (!result.isFile || result.length() == 0L) {
                result.delete()
                return false
            }
            // The user may have deleted the recording while it was converting; don't resurrect it.
            if (graph.recordingRepository.byId(recording.id) == null) {
                result.delete()
                return false
            }

            val finalLocator = if (directOutput) {
                planned
            } else {
                val sink = RecordingStorage.openSink(context, settings, planned)
                // Record the actual USB/SMB destination immediately after opening it. If the
                // following copy fails or is cancelled, catch/finally can remove the partial
                // remote file instead of losing it because targetLocator was assigned too late.
                targetLocator = sink.resolvedLocator ?: planned
                try {
                    withContext(Dispatchers.IO) {
                        result.inputStream().use { bytes -> bytes.copyTo(sink.output) }
                    }
                    sink.output.flush()
                } finally {
                    runCatching { sink.close() }
                }
                val copiedSize = RecordingStorage.sizeOf(context, settings, targetLocator!!)
                if (copiedSize != result.length()) {
                    throw IOException("Remux output size could not be verified.")
                }
                targetLocator!!
            }
            targetLocator = finalLocator
            graph.recordingRepository.setFilePath(recording.id, finalLocator)
            graph.recordingRepository.setSize(recording.id, result.length())
            // The old TS is no longer the row's locator. A failed cleanup is non-fatal here: the
            // validated MP4 remains playable, while the stale TS can be retried/cleaned later.
            runCatching { RecordingStorage.delete(context, settings, recording.filePath) }
            return true
        } catch (cancelled: CancellationException) {
            rollbackTarget()
            throw cancelled
        } catch (t: Throwable) {
            // Keep whatever is already complete on the destination out of the library.
            rollbackTarget()
            return false
        } finally {
            input.delete()
            output.delete()
        }
    }

    /** First video and audio track MIME types, or null when the file has no usable video. */
    private fun probe(file: File): Tracks? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var video: String? = null
            var audio: String? = null
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && video == null) {
                    video = mime
                } else if (mime.startsWith("audio/") && audio == null) {
                    audio = mime
                }
            }
            video?.let { Tracks(it, audio) }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Transmux with Media3. Setting the requested output MIME types to the *input* types is what
     * tells Transformer to copy the samples instead of decoding and re-encoding them.
     */
    @OptIn(UnstableApi::class)
    private suspend fun transmux(
        context: Context,
        input: File,
        output: File,
        tracks: Tracks,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val request = TransformationRequest.Builder()
                .setVideoMimeType(tracks.video)
                .apply { tracks.audio?.let { setAudioMimeType(it) } }
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setRemoveAudio(tracks.audio == null)
                .build()

            var finished = false
            fun finish(ok: Boolean) {
                if (finished) return
                finished = true
                if (continuation.isActive) continuation.resume(ok)
            }

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish(true)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    output.delete()
                    finish(false)
                }
            }

            val transformer = try {
                Transformer.Builder(context)
                    .setLooper(Looper.getMainLooper())
                    .setTransformationRequest(request)
                    .addListener(listener)
                    .build()
            } catch (t: Throwable) {
                output.delete()
                finish(false)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { runCatching { transformer.cancel() } }
            }
            try {
                transformer.start(item, output.absolutePath)
            } catch (t: Throwable) {
                output.delete()
                finish(false)
            }
        }
    }
}

/** Remove a newly opened remux target without ever deleting the original recording locator. */
internal fun rollbackRemuxTarget(
    targetLocator: String?,
    originalLocator: String,
    delete: (String) -> Boolean,
): Boolean {
    val target = targetLocator?.takeIf { it != originalLocator } ?: return true
    return delete(target)
}
