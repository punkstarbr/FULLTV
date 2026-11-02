/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleCueTextTest {
    @Test
    fun trimsBlankCuesAndDeduplicatesTextFromTheSelectedTrack() {
        val cues: List<CharSequence?> = listOf("  Olá, mundo!  ", null, "", "Olá, mundo!")

        assertThat(SubtitleCueText.from(cues)).containsExactly("Olá, mundo!")
    }

    @Test
    fun noReadableTextCuesMeansNothingIsSentForTranslation() {
        val bitmapOnlyCues: List<CharSequence?> = listOf(null, "  ", "\n")

        assertThat(SubtitleCueText.from(bitmapOnlyCues)).isEmpty()
        assertThat(SubtitleCueText.from(emptyList())).isEmpty()
    }
}
