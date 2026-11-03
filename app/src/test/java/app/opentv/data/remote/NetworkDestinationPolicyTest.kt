/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class NetworkDestinationPolicyTest {
    @After
    fun clearConfiguredOrigins() {
        NetworkDestinationPolicy.replaceTrustedOrigins(emptyList())
    }

    @Test
    fun rejectsLoopbackAndPrivateAddressLiterals() {
        listOf(
            "https://127.0.0.1/live",
            "https://10.0.0.5/live",
            "https://172.20.0.8/live",
            "https://192.168.1.10/live",
            "https://169.254.169.254/latest/meta-data",
            "https://[::1]/live",
            "https://[fc00::5]/live",
        ).forEach { assertThat(NetworkDestinationPolicy.isAllowedHttps(it)).isFalse() }
    }

    @Test
    fun privateOriginMustMatchExactConfiguredSchemeHostAndPort() {
        val configuredOrigin = "https://192.168.51.2:47531/playlist.m3u"
        assertThat(
            NetworkDestinationPolicy.isAllowedHttps(
                "https://192.168.51.2:47531/live/1",
                trustedOriginUrl = configuredOrigin,
            ),
        ).isTrue()
        assertThat(
            NetworkDestinationPolicy.isAllowedHttps(
                "https://192.168.51.2:47532/admin",
                trustedOriginUrl = configuredOrigin,
            ),
        ).isFalse()
        assertThat(
            NetworkDestinationPolicy.isAllowedHttps(
                "https://192.168.51.3:47531/live/1",
                trustedOriginUrl = configuredOrigin,
            ),
        ).isFalse()
        assertThat(NetworkDestinationPolicy.registerTrustedOrigin(configuredOrigin)).isTrue()
        assertThat(NetworkDestinationPolicy.isAllowedHttpsRequest("https://192.168.51.2:47531/live/1")).isTrue()
        assertThat(NetworkDestinationPolicy.isAllowedHttpsRequest("https://192.168.51.2:47532/admin")).isFalse()
    }

    @Test
    fun acceptsPublicAddressAndRejectsSpecialUseRanges() {
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://8.8.8.8/stream")).isTrue()
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://192.0.2.5/stream")).isFalse()
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://198.51.100.20/stream")).isFalse()
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://203.0.113.8/stream")).isFalse()
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://[64:ff9b::c0a8:101]/stream")).isFalse()
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://[2002:c0a8:0101::1]/stream")).isFalse()
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://[2001::1]/stream")).isFalse()
    }

    @Test
    fun rejectsNonHttpsUserinfoFragmentsAndLocalNames() {
        assertThat(NetworkDestinationPolicy.isAllowedHttps("http://8.8.8.8/stream")).isFalse()
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://user:pass@8.8.8.8/stream")).isFalse()
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://8.8.8.8/stream#secret")).isFalse()
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://router.local/stream")).isFalse()
        assertThat(NetworkDestinationPolicy.isAllowedHttps("https://localhost/stream")).isFalse()
    }

    @Test
    fun privateDnsExceptionRequiresTheExactRequestScope() {
        val configured = "https://192.168.51.2:47531/playlist.m3u"
        assertThat(NetworkDestinationPolicy.registerTrustedOrigin(configured)).isTrue()

        // A global allowlist must not make this private address resolvable for another operation.
        assertThat(runCatching { NetworkDestinationPolicy.pinnedPublicAddresses("192.168.51.2") }.isFailure)
            .isTrue()

        val scope = NetworkDestinationPolicy.scopeFor(configured, configured)
        val scoped = NetworkDestinationPolicy.withDnsScope(scope) {
            NetworkDestinationPolicy.pinnedPublicAddresses("192.168.51.2")
        }
        assertThat(scoped).isNotEmpty()
    }

    @Test
    fun privateIpv6OriginIsBracketedAndPinnedOnlyWithinItsScope() {
        val configured = "https://[fd12:3456::20]:47531/playlist.m3u"
        val scope = NetworkDestinationPolicy.scopeFor(configured, configured)

        assertThat(scope).isNotNull()
        assertThat(scope!!.initialOrigin).isEqualTo("https://[fd12:3456::20]:47531")
        assertThat(
            NetworkDestinationPolicy.withDnsScope(scope) {
                NetworkDestinationPolicy.pinnedPublicAddresses("fd12:3456::20")
            },
        ).isNotEmpty()
    }

    @Test
    fun crossOriginPublicRedirectsAreOptInAndPrivateTargetsRemainBlocked() {
        val strict = NetworkDestinationPolicy.scopeFor("https://8.8.8.8/start")
        assertThat(NetworkDestinationPolicy.isAllowedRedirect("https://1.1.1.1/cdn.m3u8", strict)).isFalse()

        val media = NetworkDestinationPolicy.scopeFor(
            "https://8.8.8.8/start",
            allowPublicCrossOriginRedirects = true,
        )
        assertThat(NetworkDestinationPolicy.isAllowedRedirect("https://1.1.1.1/cdn.m3u8", media)).isTrue()
        assertThat(
            NetworkDestinationPolicy.isAllowedRedirect("https://192.168.51.2:47531/admin", media),
        ).isFalse()
    }

    @Test
    fun trustedOriginA_cannot_be_a_redirect_target_of_operationB() {
        val configuredA = "https://192.168.51.2:47531/private.m3u"
        assertThat(NetworkDestinationPolicy.registerTrustedOrigin(configuredA)).isTrue()

        val operationB = NetworkDestinationPolicy.scopeFor("https://8.8.8.8/start")
        assertThat(operationB).isNotNull()
        assertThat(
            NetworkDestinationPolicy.isAllowedRedirect(
                "https://192.168.51.2:47531/admin",
                operationB,
            ),
        ).isFalse()

        NetworkDestinationPolicy.revokeTrustedOrigin(configuredA)
        assertThat(NetworkDestinationPolicy.isTrustedOrigin("https://192.168.51.2:47531/live")).isFalse()
    }
}
