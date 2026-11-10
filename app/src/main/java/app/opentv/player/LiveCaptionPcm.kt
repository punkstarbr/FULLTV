package app.opentv.player

import kotlin.math.min

/**
 * Converts signed 16-bit little-endian interleaved PCM to mono 16 kHz PCM.
 *
 * The converter is deliberately JVM-only. It keeps the last source sample and
 * the fractional resampling position so independently delivered buffers join
 * without a discontinuity. An incomplete PCM frame is retained until the next
 * call. A call is bounded to [MAX_OUTPUT_SAMPLES_PER_CALL] output samples;
 * excess input in that call is ignored rather than retained without bound.
 */
class Pcm16kStreamConverter {
    companion object {
        const val OUTPUT_SAMPLE_RATE_HZ: Int = 16_000
        const val MAX_OUTPUT_SAMPLES_PER_CALL: Int = 16_000
        private const val MIN_INPUT_SAMPLE_RATE_HZ: Int = 8_000
        private const val MAX_INPUT_SAMPLE_RATE_HZ: Int = 192_000
        private const val MAX_CHANNEL_COUNT: Int = 8
        private const val BOUNDARY_EPSILON: Double = 1.0e-9
        private val EMPTY_SHORT_ARRAY = ShortArray(0)
    }

    private var configuredSampleRateHz: Int = 0
    private var configuredChannelCount: Int = 0
    private var sourceStep: Double = 1.0

    private var hasPreviousSample = false
    private var previousSample = 0
    private var sourceFrameIndex = 0L
    private var nextOutputSourcePosition = 0.0

    // The largest accepted PCM frame is 8 channels * 2 bytes.
    private val partialFrameBytes = ByteArray(MAX_CHANNEL_COUNT * 2)
    private var partialByteCount = 0

    /**
     * Converts as much of [bytes] as fits in this bounded call.
     *
     * Invalid sample rates or channel counts return an empty result and reset
     * the stream, preventing stale samples from crossing a bad format change.
     */
    fun convert(bytes: ByteArray, sampleRateHz: Int, channelCount: Int): ShortArray {
        if (!isSupportedFormat(sampleRateHz, channelCount)) {
            reset()
            return EMPTY_SHORT_ARRAY
        }

        if (configuredSampleRateHz != sampleRateHz || configuredChannelCount != channelCount) {
            reset()
            configuredSampleRateHz = sampleRateHz
            configuredChannelCount = channelCount
            sourceStep = sampleRateHz.toDouble() / OUTPUT_SAMPLE_RATE_HZ.toDouble()
        } else if (configuredSampleRateHz == 0) {
            configuredSampleRateHz = sampleRateHz
            configuredChannelCount = channelCount
            sourceStep = sampleRateHz.toDouble() / OUTPUT_SAMPLE_RATE_HZ.toDouble()
        }

        if (bytes.isEmpty()) return EMPTY_SHORT_ARRAY

        val frameBytes = channelCount * 2
        val availableBytes = partialByteCount.toLong() + bytes.size.toLong()
        val availableFrames = (availableBytes / frameBytes.toLong()).coerceAtMost(
            maxSourceFramesPerCall(sampleRateHz).toLong(),
        ).toInt()
        if (availableFrames == 0) {
            val copyCount = min(bytes.size, partialFrameBytes.size - partialByteCount)
            if (copyCount > 0) {
                bytes.copyInto(partialFrameBytes, partialByteCount, 0, copyCount)
                partialByteCount += copyCount
            }
            return EMPTY_SHORT_ARRAY
        }

        val outputCapacity = outputCapacityFor(availableFrames, sampleRateHz)
        val output = ShortArray(outputCapacity)
        var outputCount = 0
        var inputOffset = 0
        var framesProcessed = 0

        if (partialByteCount > 0) {
            val needed = frameBytes - partialByteCount
            val copied = min(needed, bytes.size)
            bytes.copyInto(partialFrameBytes, partialByteCount, 0, copied)
            partialByteCount += copied
            inputOffset += copied
            if (partialByteCount == frameBytes) {
                outputCount = appendPcmFrame(
                    partialFrameBytes,
                    0,
                    channelCount,
                    output,
                    outputCount,
                )
                framesProcessed++
                partialByteCount = 0
            } else {
                return EMPTY_SHORT_ARRAY
            }
        }

        while (
            framesProcessed < availableFrames &&
            framesProcessed < maxSourceFramesPerCall(sampleRateHz) &&
            inputOffset + frameBytes <= bytes.size &&
            outputCount < MAX_OUTPUT_SAMPLES_PER_CALL
        ) {
            outputCount = appendPcmFrame(
                bytes,
                inputOffset,
                channelCount,
                output,
                outputCount,
            )
            inputOffset += frameBytes
            framesProcessed++
        }

        // Once the per-call source/output bound is reached, drop the rest of
        // this call. Otherwise retain only the one incomplete frame at the end.
        val reachedBound =
            outputCount >= MAX_OUTPUT_SAMPLES_PER_CALL ||
                framesProcessed >= maxSourceFramesPerCall(sampleRateHz)
        if (!reachedBound && inputOffset < bytes.size) {
            val trailing = min(bytes.size - inputOffset, frameBytes - 1)
            bytes.copyInto(partialFrameBytes, 0, inputOffset, inputOffset + trailing)
            partialByteCount = trailing
        } else if (reachedBound) {
            partialByteCount = 0
        }

        return when {
            outputCount == 0 -> EMPTY_SHORT_ARRAY
            outputCount == output.size -> output
            else -> output.copyOf(outputCount)
        }
    }

    /** Resets format, interpolation phase, and any incomplete PCM frame. */
    fun reset() {
        configuredSampleRateHz = 0
        configuredChannelCount = 0
        sourceStep = 1.0
        hasPreviousSample = false
        previousSample = 0
        sourceFrameIndex = 0L
        nextOutputSourcePosition = 0.0
        partialByteCount = 0
    }

    private fun appendPcmFrame(
        bytes: ByteArray,
        offset: Int,
        channelCount: Int,
        output: ShortArray,
        outputCount: Int,
    ): Int {
        var sum = 0
        var byteOffset = offset
        repeat(channelCount) {
            val low = bytes[byteOffset].toInt() and 0xff
            val high = bytes[byteOffset + 1].toInt()
            sum += (low or (high shl 8)).toShort().toInt()
            byteOffset += 2
        }
        val monoSample = sum / channelCount
        return appendMonoSample(monoSample, output, outputCount)
    }

    private fun appendMonoSample(
        currentSample: Int,
        output: ShortArray,
        initialOutputCount: Int,
    ): Int {
        var outputCount = initialOutputCount
        if (!hasPreviousSample) {
            hasPreviousSample = true
            previousSample = currentSample
            sourceFrameIndex = 0L
            nextOutputSourcePosition = sourceStep
            if (outputCount < output.size) {
                output[outputCount++] = currentSample.toShort()
            }
            return outputCount
        }

        val currentFrameIndex = sourceFrameIndex + 1L
        val intervalStart = (currentFrameIndex - 1L).toDouble()
        val intervalEnd = currentFrameIndex.toDouble()
        while (
            nextOutputSourcePosition <= intervalEnd + BOUNDARY_EPSILON &&
            nextOutputSourcePosition >= intervalStart - BOUNDARY_EPSILON &&
            outputCount < output.size
        ) {
            val fraction = (nextOutputSourcePosition - intervalStart).coerceIn(0.0, 1.0)
            val interpolated = previousSample +
                (currentSample - previousSample).toDouble() * fraction
            output[outputCount++] = roundedShort(interpolated)
            nextOutputSourcePosition += sourceStep
        }

        previousSample = currentSample
        sourceFrameIndex = currentFrameIndex
        return outputCount
    }

    private fun outputCapacityFor(sourceFrames: Int, sampleRateHz: Int): Int {
        val estimated = (sourceFrames.toLong() * OUTPUT_SAMPLE_RATE_HZ.toLong() +
            sampleRateHz.toLong() - 1L) / sampleRateHz.toLong() + 2L
        return estimated.coerceIn(1L, MAX_OUTPUT_SAMPLES_PER_CALL.toLong()).toInt()
    }

    private fun maxSourceFramesPerCall(sampleRateHz: Int): Int {
        val estimated = (MAX_OUTPUT_SAMPLES_PER_CALL.toLong() + 2L) * sampleRateHz.toLong()
        return ((estimated + OUTPUT_SAMPLE_RATE_HZ - 1L) / OUTPUT_SAMPLE_RATE_HZ)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun isSupportedFormat(sampleRateHz: Int, channelCount: Int): Boolean =
        sampleRateHz in MIN_INPUT_SAMPLE_RATE_HZ..MAX_INPUT_SAMPLE_RATE_HZ &&
            channelCount in 1..MAX_CHANNEL_COUNT

    private fun roundedShort(value: Double): Short {
        val rounded = if (value >= 0.0) (value + 0.5).toInt() else (value - 0.5).toInt()
        return rounded.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

internal sealed interface PcmSpeechEvent {
    data class Audio(val samples: ShortArray) : PcmSpeechEvent
    data object EndUtterance : PcmSpeechEvent
}

/**
 * A non-blocking, frame-oriented speech gate for the 16 kHz output of
 * [Pcm16kStreamConverter]. It emits bounded chunks of audio and an endpoint
 * marker; it never writes audio to disk or talks to an external service.
 */
internal class Pcm16kSpeechGate(
    private val peakThreshold: Int = DEFAULT_PEAK_THRESHOLD,
    /** Sum of squared samples per frame; zero disables this second criterion. */
    private val energyThreshold: Long = 0L,
    private val preRollFrames: Int = DEFAULT_PRE_ROLL_FRAMES,
    private val speechConfirmationFrames: Int = DEFAULT_CONFIRMATION_FRAMES,
    private val hangoverFrames: Int = DEFAULT_HANGOVER_FRAMES,
    private val maxSegmentFrames: Int = DEFAULT_MAX_SEGMENT_FRAMES,
) {
    companion object {
        const val FRAME_SAMPLES: Int = 320
        const val DEFAULT_PEAK_THRESHOLD: Int = 900
        const val DEFAULT_PRE_ROLL_FRAMES: Int = 3
        const val DEFAULT_CONFIRMATION_FRAMES: Int = 3
        const val DEFAULT_HANGOVER_FRAMES: Int = 25
        const val DEFAULT_MAX_SEGMENT_FRAMES: Int = 400
        private const val MAX_EVENTS_PER_CALL: Int = 512
    }

    private val partialFrame = ShortArray(FRAME_SAMPLES)
    private val idleBufferCapacity: Int
    private val idleFrames = ArrayDeque<ShortArray>()
    private var partialSampleCount = 0
    private var activeConfirmationCount = 0
    private var inSpeech = false
    private var silentFrameCount = 0
    private var segmentFrameCount = 0

    init {
        require(peakThreshold in 1..Short.MAX_VALUE.toInt()) {
            "peakThreshold must be between 1 and 32767"
        }
        require(energyThreshold >= 0L) { "energyThreshold must not be negative" }
        require(preRollFrames >= 0) { "preRollFrames must not be negative" }
        require(speechConfirmationFrames in 1..64) {
            "speechConfirmationFrames must be between 1 and 64"
        }
        require(hangoverFrames in 1..100) {
            "hangoverFrames must be between 1 and 100"
        }
        require(maxSegmentFrames in 1..DEFAULT_MAX_SEGMENT_FRAMES) {
            "maxSegmentFrames must be between 1 and 400"
        }
        idleBufferCapacity = preRollFrames + speechConfirmationFrames
        require(idleBufferCapacity <= 128) {
            "pre-roll plus confirmation must remain bounded"
        }
        require(maxSegmentFrames >= idleBufferCapacity) {
            "maxSegmentFrames must include the confirmation and pre-roll frames"
        }
    }

    /**
     * Accepts an arbitrarily split stream of 16 kHz mono samples.
     *
     * Samples which do not complete a 20 ms frame remain buffered. The return
     * list contains only bounded in-memory events and is safe to call from a
     * producer loop without waiting for endpoint detection.
     */
    fun push(samples: ShortArray): List<PcmSpeechEvent> {
        if (samples.isEmpty()) return emptyList()

        val estimatedEventCapacity = min(
            MAX_EVENTS_PER_CALL,
            (samples.size / FRAME_SAMPLES) + idleBufferCapacity + 2,
        ).coerceAtLeast(1)
        val events = ArrayList<PcmSpeechEvent>(estimatedEventCapacity)
        var offset = 0

        if (partialSampleCount > 0) {
            val needed = FRAME_SAMPLES - partialSampleCount
            val copied = min(needed, samples.size)
            samples.copyInto(partialFrame, partialSampleCount, 0, copied)
            partialSampleCount += copied
            offset += copied
            if (partialSampleCount == FRAME_SAMPLES) {
                processFrame(partialFrame, 0, events)
                partialSampleCount = 0
            } else {
                return events
            }
        }

        while (
            offset + FRAME_SAMPLES <= samples.size &&
            events.size < MAX_EVENTS_PER_CALL
        ) {
            processFrame(samples, offset, events)
            offset += FRAME_SAMPLES
        }

        if (events.size < MAX_EVENTS_PER_CALL && offset < samples.size) {
            val trailing = min(FRAME_SAMPLES - 1, samples.size - offset)
            samples.copyInto(partialFrame, 0, offset, offset + trailing)
            partialSampleCount = trailing
        } else if (events.size >= MAX_EVENTS_PER_CALL) {
            partialSampleCount = 0
        }

        return events
    }

    /** Discards partial frames, pre-roll, and all speech state. */
    fun reset() {
        partialSampleCount = 0
        idleFrames.clear()
        activeConfirmationCount = 0
        inSpeech = false
        silentFrameCount = 0
        segmentFrameCount = 0
    }

    private fun processFrame(
        samples: ShortArray,
        offset: Int,
        events: MutableList<PcmSpeechEvent>,
    ) {
        val active = isActive(samples, offset)
        if (!inSpeech) {
            appendIdleFrame(samples, offset)
            activeConfirmationCount = if (active) activeConfirmationCount + 1 else 0
            if (activeConfirmationCount >= speechConfirmationFrames) {
                inSpeech = true
                silentFrameCount = 0
                segmentFrameCount = 0
                while (idleFrames.isNotEmpty()) {
                    val frame = idleFrames.removeFirst()
                    emitAudio(frame, 0, events)
                    segmentFrameCount++
                }
                if (segmentFrameCount >= maxSegmentFrames) {
                    endUtterance(events)
                }
            }
            return
        }

        silentFrameCount = if (active) 0 else silentFrameCount + 1
        emitAudio(samples, offset, events)
        segmentFrameCount++
        when {
            segmentFrameCount >= maxSegmentFrames -> endUtterance(events)
            silentFrameCount >= hangoverFrames -> endUtterance(events)
        }
    }

    private fun appendIdleFrame(samples: ShortArray, offset: Int) {
        if (idleFrames.size == idleBufferCapacity) idleFrames.removeFirst()
        idleFrames.addLast(samples.copyOfRange(offset, offset + FRAME_SAMPLES))
    }

    private fun emitAudio(
        samples: ShortArray,
        offset: Int,
        events: MutableList<PcmSpeechEvent>,
    ) {
        if (events.size < MAX_EVENTS_PER_CALL) {
            events.add(PcmSpeechEvent.Audio(samples.copyOfRange(offset, offset + FRAME_SAMPLES)))
        }
    }

    private fun endUtterance(events: MutableList<PcmSpeechEvent>) {
        if (events.size < MAX_EVENTS_PER_CALL) events.add(PcmSpeechEvent.EndUtterance)
        inSpeech = false
        activeConfirmationCount = 0
        silentFrameCount = 0
        segmentFrameCount = 0
        idleFrames.clear()
    }

    private fun isActive(samples: ShortArray, offset: Int): Boolean {
        var peak = 0
        var energy = 0L
        for (index in 0 until FRAME_SAMPLES) {
            val value = samples[offset + index].toInt()
            val magnitude = if (value == Short.MIN_VALUE.toInt()) 32_768 else kotlin.math.abs(value)
            if (magnitude > peak) peak = magnitude
            if (energyThreshold > 0L) energy += value.toLong() * value.toLong()
        }
        return peak >= peakThreshold || (energyThreshold > 0L && energy >= energyThreshold)
    }
}
