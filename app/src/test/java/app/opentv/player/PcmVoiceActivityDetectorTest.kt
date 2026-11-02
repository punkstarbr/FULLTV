/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmVoiceActivityDetectorTest {

    @Test
    fun `silence is ignored`() {
        assertFalse(PcmVoiceActivityDetector.containsSpeech(FloatArray(80_000)))
    }

    @Test
    fun `low level background noise is ignored`() {
        assertFalse(PcmVoiceActivityDetector.containsSpeech(FloatArray(80_000) { 0.002f }))
    }

    @Test
    fun `sustained audio above the activity threshold is accepted`() {
        val samples = FloatArray(80_000) { index ->
            (sin(2.0 * PI * 220.0 * index / 16_000.0) * 0.1).toFloat()
        }

        assertTrue(PcmVoiceActivityDetector.containsSpeech(samples))
    }

    @Test
    fun `brief impulse is rejected as insufficient activity`() {
        val samples = FloatArray(80_000)
        samples.fill(0.1f, fromIndex = 0, toIndex = 320)

        assertFalse(PcmVoiceActivityDetector.containsSpeech(samples))
    }
}
