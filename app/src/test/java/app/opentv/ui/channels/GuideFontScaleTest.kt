/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuideFontScaleTest {
    @Test
    fun `height scales at normal and larger font scales`() {
        val baseHeightDp = 64f

        assertThat(guideFontScaledHeightDp(baseHeightDp, 1f)).isWithin(0.001f).of(64f)
        assertThat(guideFontScaledHeightDp(baseHeightDp, 1.3f)).isWithin(0.001f).of(83.2f)
        assertThat(guideFontScaledHeightDp(baseHeightDp, 1.5f)).isWithin(0.001f).of(96f)
        assertThat(guideFontScaledHeightDp(baseHeightDp, 2f)).isWithin(0.001f).of(128f)
    }

    @Test
    fun `font scale below one does not shrink the configured minimum`() {
        assertThat(guideFontScaledHeightDp(64f, 0.8f)).isWithin(0.001f).of(64f)
    }
}
