/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.GitHubM3uUrl
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitHubM3uUrlTest {
    @Test
    fun `rewrites the github raw route shared by the user to the direct raw host`() {
        assertThat(
            GitHubM3uUrl.toDirectRawUrl(
                "https://github.com/gratinomaster/1/raw/refs/heads/main/lista1.M3U",
            ),
        ).isEqualTo(
            "https://raw.githubusercontent.com/gratinomaster/1/refs/heads/main/lista1.M3U",
        )
    }

    @Test
    fun `rewrites the user MUNDO m3u GitHub URL to the direct raw host`() {
        assertThat(
            GitHubM3uUrl.toDirectRawUrl(
                "https://github.com/gratinomaster/will/raw/refs/heads/main/MUNDO.m3u",
            ),
        ).isEqualTo(
            "https://raw.githubusercontent.com/gratinomaster/will/refs/heads/main/MUNDO.m3u",
        )
    }

    @Test
    fun `rewrites a github blob page to its raw file`() {
        assertThat(
            GitHubM3uUrl.toDirectRawUrl("https://github.com/acme/iptv/blob/main/lists/live.m3u8"),
        ).isEqualTo("https://raw.githubusercontent.com/acme/iptv/main/lists/live.m3u8")
    }

    @Test
    fun `preserves branch names that contain path segments`() {
        assertThat(
            GitHubM3uUrl.toDirectRawUrl("https://github.com/acme/iptv/raw/refs/heads/feature/tv/list.m3u"),
        ).isEqualTo("https://raw.githubusercontent.com/acme/iptv/refs/heads/feature/tv/list.m3u")
    }

    @Test
    fun `does not rewrite other hosts or non playlist files`() {
        val external = "https://example.com/lists/live.m3u"
        val document = "https://github.com/acme/iptv/raw/main/README.md"

        assertThat(GitHubM3uUrl.toDirectRawUrl(external)).isEqualTo(external)
        assertThat(GitHubM3uUrl.toDirectRawUrl(document)).isEqualTo(document)
    }

    @Test
    fun `does not move queries credentials or encoded path separators to raw host`() {
        val query = "https://github.com/acme/iptv/raw/main/live.m3u?token=secret"
        val credentials = "https://user:pass@github.com/acme/iptv/raw/main/live.m3u"
        val encodedSeparator = "https://github.com/acme/iptv/raw/main%2flive.m3u"

        assertThat(GitHubM3uUrl.toDirectRawUrl(query)).isEqualTo(query)
        assertThat(GitHubM3uUrl.toDirectRawUrl(credentials)).isEqualTo(credentials)
        assertThat(GitHubM3uUrl.toDirectRawUrl(encodedSeparator)).isEqualTo(encodedSeparator)
    }

    @Test
    fun `does not rewrite alternate ports or traversal segments`() {
        val alternatePort = "https://github.com:8443/acme/iptv/raw/main/live.m3u"
        val traversal = "https://github.com/acme/iptv/raw/main/../live.m3u"
        val encodedTraversal = "https://github.com/acme/iptv/raw/main/%2e%2e/live.m3u"
        val doubleEncodedSeparator = "https://github.com/acme/iptv/raw/main%252flive.m3u"

        assertThat(GitHubM3uUrl.toDirectRawUrl(alternatePort)).isEqualTo(alternatePort)
        assertThat(GitHubM3uUrl.toDirectRawUrl(traversal)).isEqualTo(traversal)
        assertThat(GitHubM3uUrl.toDirectRawUrl(encodedTraversal)).isEqualTo(encodedTraversal)
        assertThat(GitHubM3uUrl.toDirectRawUrl(doubleEncodedSeparator)).isEqualTo(doubleEncodedSeparator)
    }
}
