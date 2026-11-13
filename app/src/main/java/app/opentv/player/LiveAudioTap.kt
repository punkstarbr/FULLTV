/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer

/** A bounded copy of decoded PCM from the current ExoPlayer output; never microphone/system audio. */
internal data class CapturedPcm(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
    val bytes: ByteArray,
)

/**
 * A non-blocking tap on Media3's PCM sink. It only copies bounded PCM while the user has explicitly
 * started live captions. The TeeAudioProcessor forwards the original buffer unchanged for playback.
 */
@OptIn(markerClass = [UnstableApi::class])
class LiveAudioTap internal constructor() : TeeAudioProcessor.AudioBufferSink, AutoCloseable {
    private data class AudioFormat(val sampleRateHz: Int, val channelCount: Int, val encoding: Int)

    private val packets = Channel<CapturedPcm>(
        capacity = MAX_QUEUED_PACKETS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile private var audioFormat = AudioFormat(0, 0, C.ENCODING_INVALID)
    @Volatile private var captureEnabled = false
    @Volatile private var closed = false

    internal val processor = TeeAudioProcessor(this)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        audioFormat = AudioFormat(sampleRateHz, channelCount, encoding)
        clearPending()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!captureEnabled || closed) return
        val format = audioFormat
        if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT ||
            format.sampleRateHz !in MIN_SAMPLE_RATE_HZ..MAX_SAMPLE_RATE_HZ ||
            format.channelCount !in 1..MAX_CHANNELS
        ) return

        val bytesPerSample = if (format.encoding == C.ENCODING_PCM_FLOAT) Float.SIZE_BYTES else Short.SIZE_BYTES
        val frameBytes = format.channelCount * bytesPerSample
        val bytesToCopy = buffer.remaining().coerceAtMost(MAX_PACKET_BYTES)
        val alignedBytes = bytesToCopy - bytesToCopy % frameBytes
        if (alignedBytes <= 0) return

        // TeeAudioProcessor owns the source buffer. Copy only the bounded, frame-aligned bytes and
        // leave the buffer position/content untouched for the normal audio sink.
        val copy = ByteArray(alignedBytes)
        val source = buffer.asReadOnlyBuffer()
        source.limit(source.position() + alignedBytes)
        source.get(copy)
        packets.trySend(
            CapturedPcm(
                sampleRateHz = format.sampleRateHz,
                channelCount = format.channelCount,
                encoding = format.encoding,
                bytes = copy,
            ),
        )
    }

    internal fun startCapture() {
        if (closed) return
        clearPending()
        captureEnabled = true
    }

    internal fun clearBufferedAudio() = clearPending()

    internal fun stopCapture() {
        captureEnabled = false
        clearPending()
    }

    internal suspend fun receive(): CapturedPcm = packets.receive()

    override fun close() {
        if (closed) return
        captureEnabled = false
        closed = true
        packets.close()
        clearPending()
    }

    private fun clearPending() {
        while (packets.tryReceive().isSuccess) Unit
    }

    private companion object {
        const val MAX_QUEUED_PACKETS = 64
        const val MAX_PACKET_BYTES = 64 * 1024
        const val MIN_SAMPLE_RATE_HZ = 8_000
        const val MAX_SAMPLE_RATE_HZ = 192_000
        const val MAX_CHANNELS = 8
    }
}

/**
 * Fullscreen-only renderer factory that tees PCM into [LiveAudioTap] without transforming the
 * samples sent to Android's audio output. Other players retain Media3's default renderer factory.
 */
@OptIn(UnstableApi::class)
internal class LiveCaptionRenderersFactory(
    context: Context,
    private val audioTap: LiveAudioTap,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(audioTap.processor))
        .build()
}
