/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.remote.HttpsRequiredException
import app.opentv.data.remote.HttpsUrlPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpsUrlPolicyTest {
    @Test
    fun `bare host defaults to HTTPS`() {
        assertThat(HttpsUrlPolicy.withDefaultHttps("provider.example:8443/live.m3u")).isEqualTo(
            "https://provider.example:8443/live.m3u",
        )
    }

    @Test
    fun `explicit HTTPS is preserved`() {
        assertThat(HttpsUrlPolicy.withDefaultHttps("HTTPS://provider.example/live.m3u")).isEqualTo(
            "HTTPS://provider.example/live.m3u",
        )
        HttpsUrlPolicy.requireHttps("HTTPS://provider.example/live.m3u")
    }

    @Test
    fun `explicit HTTP is identified and rejected`() {
        assertThat(HttpsUrlPolicy.isCleartext(" http://provider.example/live.m3u ")).isTrue()
        assertThrows(HttpsRequiredException::class.java) {
            HttpsUrlPolicy.requireHttps("http://provider.example/live.m3u")
        }
    }

    @Test
    fun `unsupported schemes are not silently upgraded`() {
        assertThrows(HttpsRequiredException::class.java) {
            HttpsUrlPolicy.requireHttps("ftp://provider.example/list.m3u")
        }
        assertThat(HttpsUrlPolicy.requiresSecureScheme("ftp://provider.example/list.m3u")).isTrue()
    }

    @Test
    fun `optional empty URL is allowed and bare host will be upgraded`() {
        HttpsUrlPolicy.requireHttps(null)
        HttpsUrlPolicy.requireHttps("  ")
        assertThat(HttpsUrlPolicy.requiresSecureScheme("provider.example:8443")).isFalse()
        assertThat(HttpsUrlPolicy.requiresSecureScheme("http://provider.example:8443")).isTrue()
    }

    @Test
    fun `rejects userinfo fragments malformed hosts and control characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpsUrlPolicy.requireHttps("https://user:pass@provider.example/list.m3u")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpsUrlPolicy.requireHttps("https://provider.example/list.m3u#fragment")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpsUrlPolicy.requireHttps("https://provider.example:bad/list.m3u")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HttpsUrlPolicy.requireHttps("https://provider.example/a\nheader")
        }
        HttpsUrlPolicy.requireHttps("https://provider.example/list.m3u?token=a%2Fb")
    }
}
