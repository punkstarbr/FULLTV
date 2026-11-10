package app.opentv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCaptionPcmTest {
    @Test
    fun `mono 16 kHz is passed through`() {
        val input = shortArrayOf(-20_000, -1, 0, 1, 20_000)
        val converter = Pcm16kStreamConverter()

        assertArrayEquals(input, converter.convert(pcm16(input), 16_000, 1))
    }

    @Test
    fun `stereo is downmixed by arithmetic mean`() {
        val left = shortArrayOf(-10_000, 10_000, 30_000)
        val right = shortArrayOf(10_000, 20_000, -30_000)
        val interleaved = ShortArray(left.size * 2)
        for (index in left.indices) {
            interleaved[index * 2] = left[index]
            interleaved[index * 2 + 1] = right[index]
        }

        val output = Pcm16kStreamConverter().convert(pcm16(interleaved), 16_000, 2)

        assertArrayEquals(shortArrayOf(0, 15_000, 0), output)
    }

    @Test
    fun `48 kHz is linearly resampled to 16 kHz`() {
        val input = ShortArray(480) { it.toShort() }
        val output = Pcm16kStreamConverter().convert(pcm16(input), 48_000, 1)

        assertEquals(160, output.size)
        assertEquals(0, output[0].toInt())
        assertEquals(3, output[1].toInt())
        assertEquals(477, output.last().toInt())
    }

    @Test
    fun `44 point 1 kHz is resampled with fractional linear positions`() {
        val input = ShortArray(441) { it.toShort() }
        val output = Pcm16kStreamConverter().convert(pcm16(input), 44_100, 1)

        assertEquals(160, output.size)
        assertEquals(0, output[0].toInt())
        assertEquals(3, output[1].toInt())
        assertEquals(6, output[2].toInt())
        assertEquals(8, output[3].toInt())
        for (index in 0 until output.lastIndex) {
            assertTrue(output[index + 1] >= output[index])
        }
    }

    @Test
    fun `resampling is continuous across arbitrary byte buffer boundaries`() {
        val input = ShortArray(441) { ((it * 97) % 20_000 - 10_000).toShort() }
        val pcm = pcm16(input)
        val whole = Pcm16kStreamConverter().convert(pcm, 44_100, 1)
        val chunkedConverter = Pcm16kStreamConverter()
        val chunked = buildList {
            addAll(chunkedConverter.convert(pcm.copyOfRange(0, 1), 44_100, 1).toList())
            addAll(chunkedConverter.convert(pcm.copyOfRange(1, 137), 44_100, 1).toList())
            addAll(chunkedConverter.convert(pcm.copyOfRange(137, 509), 44_100, 1).toList())
            addAll(chunkedConverter.convert(pcm.copyOfRange(509, pcm.size), 44_100, 1).toList())
        }.toShortArray()

        assertArrayEquals(whole, chunked)
    }

    @Test
    fun `format changes reset interpolation and incomplete frame state`() {
        val converter = Pcm16kStreamConverter()
        converter.convert(pcm16(shortArrayOf(30_000, 30_000, 30_000)), 48_000, 1)
        converter.convert(byteArrayOf(0x34), 48_000, 1)

        val output = converter.convert(
            pcm16(shortArrayOf(-12_345, -12_345)),
            16_000,
            1,
        )

        assertArrayEquals(shortArrayOf(-12_345, -12_345), output)
    }

    @Test
    fun `invalid formats are rejected safely and output is bounded`() {
        val converter = Pcm16kStreamConverter()
        assertTrue(converter.convert(ByteArray(100), 7_999, 1).isEmpty())
        assertTrue(converter.convert(ByteArray(100), 16_000, 0).isEmpty())

        val oversized = converter.convert(ByteArray(16_000 * 2 * 4), 8_000, 1)
        assertTrue(oversized.size <= Pcm16kStreamConverter.MAX_OUTPUT_SAMPLES_PER_CALL)
    }

    @Test
    fun `silence is rejected without emitting audio or endpoint`() {
        val gate = Pcm16kSpeechGate(
            peakThreshold = 1_000,
            preRollFrames = 2,
            speechConfirmationFrames = 2,
            hangoverFrames = 3,
        )

        val events = gate.push(ShortArray(Pcm16SpeechGateSamples.FRAME_SAMPLES * 40))

        assertTrue(events.isEmpty())
    }

    @Test
    fun `speech confirmation emits pre roll and hangover before endpoint`() {
        val gate = Pcm16kSpeechGate(
            peakThreshold = 1_000,
            preRollFrames = 1,
            speechConfirmationFrames = 2,
            hangoverFrames = 3,
            maxSegmentFrames = 20,
        )
        val silence = ShortArray(Pcm16SpeechGateSamples.FRAME_SAMPLES)
        val speech = shortArrayOfFrame(4_000)
        val first = concat(silence, speech, speech)
        val second = concat(silence, silence, silence)

        val events = gate.push(first) + gate.push(second)
        val audio = events.filterIsInstance<PcmSpeechEvent.Audio>()

        assertEquals(6, audio.size)
        assertTrue(audio.first().samples.all { it == 0.toShort() })
        assertTrue(audio[1].samples.all { it == 4_000.toShort() })
        assertTrue(audio[2].samples.all { it == 4_000.toShort() })
        assertTrue(events.last() === PcmSpeechEvent.EndUtterance)
    }

    @Test
    fun `continuous speech ends at the configured segment ceiling`() {
        val gate = Pcm16kSpeechGate(
            peakThreshold = 1_000,
            preRollFrames = 0,
            speechConfirmationFrames = 1,
            hangoverFrames = 5,
            maxSegmentFrames = 5,
        )

        val events = gate.push(shortArrayOfFrame(3_000, frameCount = 8))
        val firstEnd = events.indexOfFirst { it === PcmSpeechEvent.EndUtterance }

        assertEquals(5, firstEnd)
        assertEquals(5, events.take(firstEnd).filterIsInstance<PcmSpeechEvent.Audio>().size)
    }

    @Test
    fun `gate retains partial frames between calls and reset discards them`() {
        val gate = Pcm16kSpeechGate(
            peakThreshold = 1_000,
            preRollFrames = 0,
            speechConfirmationFrames = 1,
            hangoverFrames = 2,
            maxSegmentFrames = 10,
        )
        val frame = shortArrayOfFrame(3_000)

        assertTrue(gate.push(frame.copyOfRange(0, 100)).isEmpty())
        val completed = gate.push(frame.copyOfRange(100, frame.size))
        assertEquals(1, completed.filterIsInstance<PcmSpeechEvent.Audio>().size)

        gate.reset()
        assertTrue(gate.push(frame.copyOfRange(100, frame.size)).isEmpty())
    }

    @Test
    fun `energy threshold can activate a low peak frame`() {
        val gate = Pcm16kSpeechGate(
            peakThreshold = 30_000,
            energyThreshold = 320L * 2_000L * 2_000L,
            preRollFrames = 0,
            speechConfirmationFrames = 1,
            hangoverFrames = 2,
            maxSegmentFrames = 10,
        )

        val events = gate.push(shortArrayOfFrame(2_000))

        assertFalse(events.isEmpty())
        assertTrue(events.first() is PcmSpeechEvent.Audio)
    }

    private fun pcm16(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toInt().and(0xff).toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
        return bytes
    }

    private fun shortArrayOfFrame(value: Int, frameCount: Int = 1): ShortArray =
        ShortArray(Pcm16kSpeechGate.FRAME_SAMPLES * frameCount) { value.toShort() }

    private fun concat(vararg arrays: ShortArray): ShortArray {
        val result = ShortArray(arrays.sumOf { it.size })
        var offset = 0
        arrays.forEach { array ->
            array.copyInto(result, offset)
            offset += array.size
        }
        return result
    }

    private object Pcm16SpeechGateSamples {
        const val FRAME_SAMPLES: Int = Pcm16kSpeechGate.FRAME_SAMPLES
    }
}
