/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** A short mono 16 kHz PCM window, ready to be written as a WAV file for Whisper. */
internal data class PcmAudioWindow(
    val sequence: Int,
    val samples: FloatArray,
    val hasVoiceActivity: Boolean = true,
)

/** Lightweight energy-based VAD. Runs on the PCM worker, never on Media3's playback thread. */
internal object PcmVoiceActivityDetector {
    private const val FRAME_SAMPLES = 320 // 20 ms at the normalized 16 kHz sample rate.
    private const val MIN_RMS = 0.008f
    private const val MIN_PEAK = 0.020f
    private const val MIN_ACTIVE_FRAMES = 4

    fun containsSpeech(samples: FloatArray): Boolean {
        if (samples.size < FRAME_SAMPLES * MIN_ACTIVE_FRAMES) return false

        var activeFrames = 0
        var frameStart = 0
        while (frameStart < samples.size) {
            val frameEnd = (frameStart + FRAME_SAMPLES).coerceAtMost(samples.size)
            var squareSum = 0.0
            var peak = 0f
            for (index in frameStart until frameEnd) {
                val sample = samples[index]
                squareSum += sample.toDouble() * sample
                peak = maxOf(peak, abs(sample))
            }
            val frameSize = frameEnd - frameStart
            val rms = sqrt(squareSum / frameSize).toFloat()
            if (rms >= MIN_RMS && peak >= MIN_PEAK) activeFrames++
            frameStart = frameEnd
        }
        return activeFrames >= MIN_ACTIVE_FRAMES
    }
}

/**
 * Non-blocking Media3 tee sink. The playback thread only copies a bounded PCM buffer into a
 * drop-oldest queue. Down-mixing, resampling, and window assembly happen on a worker coroutine.
 */
internal class LivePcmAudioCapture(
    private val onWindow: (PcmAudioWindow) -> Unit,
) : TeeAudioProcessor.AudioBufferSink, AutoCloseable {

    private data class Format(val sampleRateHz: Int, val channelCount: Int, val encoding: Int)
    private sealed interface Event {
        data object Reset : Event
        data class Audio(val bytes: ByteArray, val format: Format) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<Event>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile private var format = Format(0, 0, -1)
    @Volatile private var enabled = false
    @Volatile private var closed = false

    init {
        scope.launch {
            val builder = PcmStreamWindowBuilder()
            for (event in events) {
                when (event) {
                    Event.Reset -> builder.reset()
                    is Event.Audio -> {
                        event.bytes.let { bytes ->
                                builder.push(bytes, event.format.sampleRateHz, event.format.channelCount)
                                    .filter(PcmAudioWindow::hasVoiceActivity)
                                    .forEach(onWindow)
                        }
                    }
                }
            }
        }
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        format = Format(sampleRateHz, channelCount, encoding)
        events.trySend(Event.Reset)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled || closed) return
        val currentFormat = format
        if (currentFormat.encoding != C.ENCODING_PCM_16BIT ||
            currentFormat.sampleRateHz <= 0 || currentFormat.channelCount <= 0
        ) return

        val frameSize = currentFormat.channelCount * Short.SIZE_BYTES
        val byteCount = buffer.remaining() - buffer.remaining() % frameSize
        if (byteCount <= 0) return

        // TeeAudioProcessor owns the source buffer. Copy only the bytes we will consume so neither
        // its position nor the audio sink's output buffer is modified.
        val copy = ByteArray(byteCount)
        val source = buffer.duplicate()
        source.limit(source.position() + byteCount)
        source.get(copy)
        events.trySend(Event.Audio(copy, currentFormat))
    }

    fun setEnabled(value: Boolean) {
        if (closed) return
        enabled = value
        if (!value) events.trySend(Event.Reset)
    }

    override fun close() {
        if (closed) return
        closed = true
        enabled = false
        events.close()
        scope.cancel()
    }
}

/** Converts 16-bit interleaved PCM into mono 16 kHz, emitting 5-second windows every 3 seconds. */
internal class PcmStreamWindowBuilder {
    private var currentRate = 0
    private var currentChannels = 0
    private var sourceFrames = 0L
    private var outputSamples = 0L
    private var nextSequence = 0
    private val samples = ArrayList<Float>(WINDOW_SAMPLES + OVERLAP_SAMPLES)

    fun reset() {
        currentRate = 0
        currentChannels = 0
        sourceFrames = 0L
        outputSamples = 0L
        nextSequence = 0
        samples.clear()
    }

    fun push(pcm16: ByteArray, sampleRateHz: Int, channelCount: Int): List<PcmAudioWindow> {
        if (sampleRateHz <= 0 || channelCount <= 0) return emptyList()
        if (currentRate != sampleRateHz || currentChannels != channelCount) {
            reset()
            currentRate = sampleRateHz
            currentChannels = channelCount
        }

        val frameSize = channelCount * Short.SIZE_BYTES
        val frames = pcm16.size / frameSize
        if (frames == 0) return emptyList()
        val input = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        val windows = ArrayList<PcmAudioWindow>()

        repeat(frames) {
            var mixed = 0f
            repeat(channelCount) { mixed += input.short / 32768f }
            val mono = (mixed / channelCount).coerceIn(-1f, 1f)
            sourceFrames++
            val targetSamples = sourceFrames * TARGET_SAMPLE_RATE / sampleRateHz
            while (outputSamples < targetSamples) {
                samples += mono
                outputSamples++
                if (samples.size >= WINDOW_SAMPLES) {
                    val window = FloatArray(WINDOW_SAMPLES) { samples[it] }
                    windows += PcmAudioWindow(
                        sequence = nextSequence++,
                        samples = window,
                        hasVoiceActivity = PcmVoiceActivityDetector.containsSpeech(window),
                    )
                    val retained = ArrayList(samples.subList(HOP_SAMPLES, samples.size))
                    samples.clear()
                    samples.addAll(retained)
                }
            }
        }
        return windows
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val WINDOW_SECONDS = 5
        const val HOP_SECONDS = 3
        const val WINDOW_SAMPLES = TARGET_SAMPLE_RATE * WINDOW_SECONDS
        const val HOP_SAMPLES = TARGET_SAMPLE_RATE * HOP_SECONDS
        const val OVERLAP_SAMPLES = WINDOW_SAMPLES - HOP_SAMPLES
    }
}
