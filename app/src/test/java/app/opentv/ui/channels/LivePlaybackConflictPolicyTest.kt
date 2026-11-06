/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LivePlaybackConflictPolicyTest {
    @Test
    fun `YouTube live does not trigger an IPTV account quota warning`() {
        assertThat(
            LivePlaybackConflictPolicy.needsProviderWarning(
                isYouTubeLive = true,
                playbackSourceId = 7,
                activeRecordingSourceIds = listOf(7, 7),
            ),
        ).isFalse()
    }

    @Test
    fun `recording from another source does not trigger this provider warning`() {
        assertThat(
            LivePlaybackConflictPolicy.needsProviderWarning(
                isYouTubeLive = false,
                playbackSourceId = 7,
                activeRecordingSourceIds = listOf(4, 5),
            ),
        ).isFalse()
    }

    @Test
    fun `same IPTV source warns but does not define the provider quota`() {
        assertThat(
            LivePlaybackConflictPolicy.needsProviderWarning(
                isYouTubeLive = false,
                playbackSourceId = 7,
                activeRecordingSourceIds = listOf(4, 7),
            ),
        ).isTrue()
    }

    @Test
    fun `no active recordings means no warning`() {
        assertThat(
            LivePlaybackConflictPolicy.needsProviderWarning(
                isYouTubeLive = false,
                playbackSourceId = 7,
                activeRecordingSourceIds = emptyList(),
            ),
        ).isFalse()
    }
}
