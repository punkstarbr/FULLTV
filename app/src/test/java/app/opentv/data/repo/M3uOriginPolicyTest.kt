/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.remote.NetworkDestinationPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uOriginPolicyTest {
    @Test
    fun `guide origins have a separate budget from many media origins`() {
        val media = (0 until 300).map { "https://media$it.example/channel.m3u8" }
        val guides = (0 until 10).map { "https://guide$it.example/tv.xml" }

        val allowed = M3uOriginPolicy.selectAllowedOrigins(
            trustedOrigin = "https://playlist.example:443",
            mediaUrls = media,
            guideUrls = guides,
            isAllowedHttpsOrigin = { true },
        )

        assertThat(allowed).hasSize(1 + M3uOriginPolicy.MAX_SAFE_MEDIA_ORIGINS + 10)
        assertThat(allowed).contains("https://media127.example:443")
        assertThat(allowed).doesNotContain("https://media128.example:443")
        assertThat(allowed)
            .containsAtLeastElementsIn(guides.mapNotNull(NetworkDestinationPolicy::originOf))
    }

    @Test
    fun `trusted origin does not consume either budget and unsafe guides are excluded`() {
        val trusted = "https://playlist.example:443"
        val guides = listOf(
            "https://private.example/guide.xml",
            "https://guide.example/guide.xml",
        )

        val allowed = M3uOriginPolicy.selectAllowedOrigins(
            trustedOrigin = trusted,
            mediaUrls = listOf("https://media.example/live.m3u8", trusted),
            guideUrls = guides,
            isAllowedHttpsOrigin = { it != "https://private.example:443" },
        )

        assertThat(allowed).contains(trusted)
        assertThat(allowed).contains("https://media.example:443")
        assertThat(allowed).contains("https://guide.example:443")
        assertThat(allowed).doesNotContain("https://private.example:443")
    }
}
