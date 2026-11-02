/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import app.opentv.data.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingUrlPolicyTest {

    @Test
    fun `Xtream HLS URL selects sibling transport stream endpoint`() {
        assertEquals(
            "https://provider.example/live/user/pass/42.ts",
            RecordingUrlPolicy.forCapture(
                "https://provider.example/live/user/pass/42.m3u8",
                SourceKind.XTREAM,
            ),
        )
    }

    @Test
    fun `Xtream conversion preserves query and fragment`() {
        assertEquals(
            "https://provider.example/live/42.ts?token=abc#live",
            RecordingUrlPolicy.forCapture(
                "https://provider.example/live/42.m3u8?token=abc#live",
                SourceKind.XTREAM,
            ),
        )
    }

    @Test
    fun `generic M3U HLS URL is not rewritten`() {
        val url = "https://playlist.example/channel.m3u8?auth=abc"

        assertEquals(url, RecordingUrlPolicy.forCapture(url, SourceKind.M3U))
    }

    @Test
    fun `Stalker URL is not rewritten`() {
        val url = "https://portal.example/live/stream.m3u8"

        assertEquals(url, RecordingUrlPolicy.forCapture(url, SourceKind.STALKER))
    }
}
