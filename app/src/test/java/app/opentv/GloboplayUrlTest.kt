/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.player.GloboplayManifestUrlPolicy
import app.opentv.player.GloboplayPageUrl
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GloboplayUrlTest {
    @Test
    fun `only the four supplied public video pages are canonicalized`() {
        val ids = listOf("1328766", "992055", "4064559", "4218681")
        ids.forEach { id ->
            assertThat(GloboplayPageUrl.canonicalize("https://globoplay.globo.com/v/$id/"))
                .isEqualTo("https://globoplay.globo.com/v/$id/")
        }
    }

    @Test
    fun `page canonicalization removes tracking query and accepts optional trailing slash`() {
        assertThat(
            GloboplayPageUrl.canonicalize("https://globoplay.globo.com/v/1328766?utm_source=test"),
        ).isEqualTo("https://globoplay.globo.com/v/1328766/")
    }

    @Test
    fun `page canonicalization rejects other ids paths and hosts`() {
        listOf(
            "https://globoplay.globo.com/v/10248083/",
            "https://globoplay.globo.com/v/1328766/extra",
            "https://globoplay.globo.com.evil.example/v/1328766/",
            "https://example.com/v/1328766/",
            "https://globoplay.globo.com.evil.example/v/1328766/",
            "not a URL",
        ).forEach { assertThat(GloboplayPageUrl.canonicalize(it)).isNull() }
    }

    @Test
    fun `page canonicalization rejects insecure or ambiguous authorities`() {
        listOf(
            "http://globoplay.globo.com/v/1328766/",
            "https://user@globoplay.globo.com/v/1328766/",
            "https://globoplay.globo.com:8443/v/1328766/",
            "https://globoplay.globo.com/v/1328766/#fragment",
            "https://globoplay.globo.com/v/1328766/../../v/992055/",
        ).forEach { assertThat(GloboplayPageUrl.canonicalize(it)).isNull() }
    }

    @Test
    fun `manifest policy accepts only public Globo live HTTPS M3U8 URL syntax`() {
        assertThat(
            GloboplayManifestUrlPolicy.isAllowed(
                "https://live-cm-ah-24-31.video.globo.com/channel/master.m3u8",
            ),
        ).isTrue()
        assertThat(
            GloboplayManifestUrlPolicy.isAllowed(
                "https://LIVE-CM-AH-18-35.video.globo.com/channel/index.M3U8",
            ),
        ).isTrue()
    }

    @Test
    fun `manifest policy rejects lookalike hosts credentials queries ports and traversal`() {
        listOf(
            "http://live-cm-ah-24-31.video.globo.com/channel/master.m3u8",
            "https://live-cm-ah-24-31.video.globo.com.evil.example/channel/master.m3u8",
            "https://user@live-cm-ah-24-31.video.globo.com/channel/master.m3u8",
            "https://live-cm-ah-24-31.video.globo.com:8443/channel/master.m3u8",
            "https://live-cm-ah-24-31.video.globo.com/channel/master.m3u8?token=redacted",
            "https://live-cm-ah-24-31.video.globo.com/channel/%2e%2e/master.m3u8",
            "https://live-cm-ah-24-31.video.globo.com/channel/master.mp4",
            "https://other.video.globo.com/channel/master.m3u8",
        ).forEach { assertThat(GloboplayManifestUrlPolicy.isAllowed(it)).isFalse() }
    }

    @Test
    fun `manifest path detection still recognizes a query URL so it can be blocked`() {
        assertThat(
            GloboplayManifestUrlPolicy.hasM3u8Path(
                "https://live-cm-ah-24-31.video.globo.com/channel/master.m3u8?token=redacted",
            ),
        ).isTrue()
    }
}
