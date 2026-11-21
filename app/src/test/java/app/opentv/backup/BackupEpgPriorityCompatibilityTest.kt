/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.backup

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

class BackupEpgPriorityCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy global guide backup without priority still decodes`() {
        val feed = json.decodeFromString<BackupEpgFeed>(
            """{"name":"Guide","url":"https://example.invalid/guide.xml","enabled":true,"builtIn":false}""",
        )

        assertThat(feed.priority).isNull()
    }

    @Test
    fun `legacy source backup without guide priority metadata still decodes`() {
        val source = json.decodeFromString<BackupSource>(
            """{"name":"Playlist","kind":"M3U","url":"https://example.invalid/list.m3u","userAgent":"","liveFormat":"HLS","enabled":true}""",
        )

        assertThat(source.providerEpgPriority).isNull()
        assertThat(source.playlistEpgPriorities).isEmpty()
    }
}
