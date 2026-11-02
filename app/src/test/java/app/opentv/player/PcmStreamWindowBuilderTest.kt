/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmStreamWindowBuilderTest {

    @Test
    fun `downmixes stereo PCM and emits 5 second 16 kHz windows with 2 second overlap`() {
        val builder = PcmStreamWindowBuilder()
        val first = builder.push(stereoPcm16(frames = 48_000 * 5, sample = 16_384), 48_000, 2)

        assertEquals(1, first.size)
        assertEquals(0, first.single().sequence)
        assertEquals(80_000, first.single().samples.size)
        assertEquals(0.5f, first.single().samples[20_000], 0.001f)

        val second = builder.push(stereoPcm16(frames = 48_000 * 3, sample = 16_384), 48_000, 2)
        assertEquals(1, second.size)
        assertEquals(1, second.single().sequence)
        assertEquals(80_000, second.single().samples.size)
    }

    @Test
    fun `resamples 44 point 1 kHz without exceeding window boundaries`() {
        val builder = PcmStreamWindowBuilder()
        val windows = builder.push(stereoPcm16(frames = 44_100 * 5, sample = 8_192), 44_100, 2)

        assertEquals(1, windows.size)
        assertEquals(80_000, windows.single().samples.size)
        assertEquals(0.25f, windows.single().samples[40_000], 0.001f)
        assertTrue(windows.single().samples.all { it in -1f..1f })
    }

    @Test
    fun `format change resets buffered samples and sequence`() {
        val builder = PcmStreamWindowBuilder()
        builder.push(stereoPcm16(frames = 48_000 * 2, sample = 16_384), 48_000, 2)

        val afterFormatChange = builder.push(monoPcm16(frames = 48_000 * 5, sample = -8_192), 48_000, 1)

        assertEquals(1, afterFormatChange.size)
        assertEquals(0, afterFormatChange.single().sequence)
        assertEquals(-0.25f, afterFormatChange.single().samples[30_000], 0.001f)
    }

    @Test
    fun `silent windows are marked inactive for the transcription worker`() {
        val builder = PcmStreamWindowBuilder()

        val windows = builder.push(monoPcm16(frames = 48_000 * 5, sample = 0), 48_000, 1)

        assertEquals(1, windows.size)
        assertFalse(windows.single().hasVoiceActivity)
    }

    private fun stereoPcm16(frames: Int, sample: Short): ByteArray {
        val buffer = ByteBuffer.allocate(frames * 2 * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            buffer.putShort(sample)
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    private fun monoPcm16(frames: Int, sample: Short): ByteArray {
        val buffer = ByteBuffer.allocate(frames * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { buffer.putShort(sample) }
        return buffer.array()
    }
}
