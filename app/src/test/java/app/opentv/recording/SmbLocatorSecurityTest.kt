/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmbLocatorSecurityTest {
    private val config = SmbConfig(
        host = "192.168.1.20",
        share = "Recordings",
        folder = "OpenTV/recordings",
        username = "tv",
        password = "not-used-by-these-tests",
    )

    @Test
    fun `generated locator is safe and portable relative path stays below configured folder`() {
        val locator = SmbClient.locator(config, "News at 8 (a1b2c3d4).ts")

        assertThat(locator).isEqualTo("smb://192.168.1.20/Recordings/OpenTV/recordings/News at 8 (a1b2c3d4).ts")
        assertThat(SmbClient.isValidLocator(config, locator)).isTrue()
        assertThat(SmbClient.relativePath(config, locator)).isEqualTo("News at 8 (a1b2c3d4).ts")
        assertThat(SmbClient.locatorForRelativePath(config, "News at 8 (a1b2c3d4).ts")).isEqualTo(locator)
    }

    @Test
    fun `remote locators must match the configured host share and root folder`() {
        assertThat(SmbClient.isValidLocator(config, "smb://other-host/Recordings/OpenTV/recordings/a.ts")).isFalse()
        assertThat(SmbClient.isValidLocator(config, "smb://192.168.1.20/Other/OpenTV/recordings/a.ts")).isFalse()
        assertThat(SmbClient.isValidLocator(config, "smb://192.168.1.20/Recordings/OpenTV/other/a.ts")).isFalse()
        assertThat(SmbClient.isValidLocator(config, "smb://192.168.1.20/Recordings/OpenTV/recordings/a.ts")).isTrue()
    }

    @Test
    fun `locators reject traversal credentials ports encoded separators and query parts`() {
        val attacks = listOf(
            "smb://192.168.1.20/Recordings/OpenTV/recordings/../secret.ts",
            "smb://192.168.1.20/Recordings/OpenTV/recordings/%2e%2e/secret.ts",
            "smb://tv:pass@192.168.1.20/Recordings/OpenTV/recordings/a.ts",
            "smb://192.168.1.20:445/Recordings/OpenTV/recordings/a.ts",
            "smb://192.168.1.20/Recordings/OpenTV/recordings/a.ts?x=1",
            "smb://192.168.1.20/Recordings/OpenTV/recordings/a.ts#fragment",
            "smb://192.168.1.20/Recordings/OpenTV/recordings//a.ts",
        )

        attacks.forEach { locator -> assertThat(SmbClient.isValidLocator(config, locator)).isFalse() }
    }

    @Test
    fun `config rejects absolute and traversing folders`() {
        val absolute = config.copy(folder = "/OpenTV")
        val traversal = config.copy(folder = "OpenTV/../private")

        assertThat(runCatching { SmbClient.locator(absolute, "a.ts") }.isFailure).isTrue()
        assertThat(runCatching { SmbClient.locator(traversal, "a.ts") }.isFailure).isTrue()
    }

    @Test
    fun `relative recording paths cannot escape configured root`() {
        val nested = SmbClient.locatorForRelativePath(config, "series/episode-01.ts")
        assertThat(SmbClient.isValidLocator(config, nested)).isTrue()
        assertThat(SmbClient.relativePath(config, nested)).isEqualTo("series/episode-01.ts")
        assertThat(runCatching { SmbClient.locatorForRelativePath(config, "../secret.ts") }.isFailure).isTrue()
        assertThat(runCatching { SmbClient.locatorForRelativePath(config, "/secret.ts") }.isFailure).isTrue()
    }
}
