/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitHubRawResourceUrlTest {
    @Test
    fun `rewrites github raw XMLTV routes including a path without an extension`() {
        assertThat(
            GitHubRawResourceUrl.toDirectRawUrl(
                "https://github.com/acme/guides/raw/refs/heads/main/region.xml.gz",
            ),
        ).isEqualTo(
            "https://raw.githubusercontent.com/acme/guides/refs/heads/main/region.xml.gz",
        )
        assertThat(
            GitHubRawResourceUrl.toDirectRawUrl(
                "https://github.com/acme/guides/raw/master/SamsungTVPlus/all",
            ),
        ).isEqualTo(
            "https://raw.githubusercontent.com/acme/guides/master/SamsungTVPlus/all",
        )
    }

    @Test
    fun `rewrites blob routes but leaves already direct raw links unchanged`() {
        assertThat(
            GitHubRawResourceUrl.toDirectRawUrl("https://github.com/acme/guides/blob/main/guide.xml"),
        ).isEqualTo("https://raw.githubusercontent.com/acme/guides/main/guide.xml")
        val direct = "https://raw.githubusercontent.com/acme/guides/main/guide.xml"
        assertThat(GitHubRawResourceUrl.toDirectRawUrl(direct)).isEqualTo(direct)
    }

    @Test
    fun `does not move queries credentials or unsafe path segments to another host`() {
        val query = "https://github.com/acme/guides/raw/main/guide.xml?signature=redacted"
        val credentials = "https://user:pass@github.com/acme/guides/raw/main/guide.xml"
        val traversal = "https://github.com/acme/guides/raw/main/%2e%2e/guide.xml"
        val encodedSeparator = "https://github.com/acme/guides/raw/main%2fguide.xml"

        assertThat(GitHubRawResourceUrl.toDirectRawUrl(query)).isEqualTo(query)
        assertThat(GitHubRawResourceUrl.toDirectRawUrl(credentials)).isEqualTo(credentials)
        assertThat(GitHubRawResourceUrl.toDirectRawUrl(traversal)).isEqualTo(traversal)
        assertThat(GitHubRawResourceUrl.toDirectRawUrl(encodedSeparator)).isEqualTo(encodedSeparator)
    }
}
