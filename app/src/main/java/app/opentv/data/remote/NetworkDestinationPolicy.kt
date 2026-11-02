/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Filters network destinations supplied by remote catalogues/add-ons. A source URL entered by the
 * user is trusted only at its exact HTTPS origin; a different origin must resolve only to globally
 * routable addresses. Validated public DNS answers are briefly pinned and reused by OkHttp to reduce
 * DNS rebinding between validation and playback.
 */
object NetworkDestinationPolicy {
    private const val CACHE_TTL_MILLIS = 60_000L
    private const val MAX_CACHE_ENTRIES = 512
    private const val MAX_TRUSTED_ORIGINS = 512
    private val resolvedHosts = ConcurrentHashMap<String, CachedHost>()
    private val trustedOrigins = ConcurrentHashMap.newKeySet<String>()
    private val cacheLock = Any()
    private val trustedLock = Any()

    private data class CachedHost(val addresses: List<InetAddress>, val expiresAt: Long)

    fun hostOf(raw: String): String? = runCatching { URI(raw).host }
        .getOrNull()?.let(::normalizeHost)

    /** Canonical HTTPS origin without path, query, credentials or fragment. */
    fun originOf(raw: String): String? {
        if (raw.length !in 9..16_384 || raw.any { it.code <= 0x20 || it.code == 0x7f }) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.rawFragment != null) {
            return null
        }
        val host = normalizeHost(uri.host) ?: return null
        if (uri.port == 0 || uri.port > 65_535) return null
        val port = if (uri.port == -1) 443 else uri.port
        return "https://$host:$port"
    }

    /** Call only for a URL that the user explicitly configured or approved in the app. */
    fun registerTrustedOrigin(raw: String): Boolean {
        val origin = originOf(raw) ?: return false
        if (origin in trustedOrigins) return true
        synchronized(trustedLock) {
            if (trustedOrigins.size >= MAX_TRUSTED_ORIGINS) return false
            trustedOrigins += origin
        }
        return true
    }

    fun isTrustedOrigin(raw: String): Boolean = originOf(raw)?.let(trustedOrigins::contains) == true

    fun isAllowedHttpsRequest(raw: String): Boolean {
        if (isTrustedOrigin(raw)) return true
        return isAllowedHttps(raw)
    }

    fun isAllowedHttps(raw: String, trustedOriginUrl: String? = null): Boolean {
        val origin = originOf(raw) ?: return false
        val host = hostOf(raw) ?: return false
        if (trustedOriginUrl != null && origin == originOf(trustedOriginUrl)) return true
        return publicAddresses(host) != null
    }

    fun isPublicInternetHost(rawHost: String): Boolean = publicAddresses(rawHost) != null

    /** Return previously validated public addresses for an OkHttp DNS implementation. */
    fun pinnedPublicAddresses(rawHost: String): List<InetAddress>? {
        val host = normalizeHost(rawHost) ?: return null
        val entry = resolvedHosts[host] ?: return null
        val now = System.currentTimeMillis()
        if (now < entry.expiresAt) {
            if (entry.addresses.isEmpty()) throw UnknownHostException("Host is not globally routable.")
            return entry.addresses
        }
        // Once a host has been validated, do not fall back to arbitrary DNS if its answer changes.
        return publicAddresses(host) ?: throw UnknownHostException("Validated host no longer resolves publicly.")
    }

    private fun publicAddresses(rawHost: String): List<InetAddress>? {
        val host = normalizeHost(rawHost) ?: return null
        if (isLocalName(host)) return null
        val now = System.currentTimeMillis()
        resolvedHosts[host]?.let { cached ->
            if (now < cached.expiresAt) return cached.addresses.takeIf { it.isNotEmpty() }
            resolvedHosts.remove(host, cached)
        }
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }
            .getOrNull()?.takeIf { it.isNotEmpty() && it.all(::isGloballyRoutable) }.orEmpty()
        synchronized(cacheLock) {
            val currentTime = System.currentTimeMillis()
            resolvedHosts.entries.removeIf { currentTime >= it.value.expiresAt }
            if (resolvedHosts.size >= MAX_CACHE_ENTRIES && !resolvedHosts.containsKey(host)) return null
            resolvedHosts[host] = CachedHost(addresses, currentTime + CACHE_TTL_MILLIS)
        }
        return addresses.takeIf { it.isNotEmpty() }
    }

    private fun normalizeHost(rawHost: String?): String? = rawHost
        ?.trim('[', ']')
        ?.trimEnd('.')
        ?.lowercase()
        ?.takeIf { it.isNotBlank() && it.length <= 253 }

    private fun isLocalName(host: String): Boolean =
        host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".lan") ||
            host.endsWith(".home") ||
            host.endsWith(".internal") ||
            host.endsWith(".test") ||
            host.endsWith(".invalid") ||
            host.endsWith(".onion")

    private fun isGloballyRoutable(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        if (address is Inet4Address) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            if (a == 0 || a >= 224 || (a == 100 && b in 64..127) ||
                (a == 192 && (b == 0 || b == 2 || b == 88 && c == 99 || b == 168)) ||
                (a == 198 && (b in 18..19 || b == 51 && c == 100)) ||
                (a == 203 && b == 0 && c == 113)
            ) return false
            return true
        }
        if (address is Inet6Address) {
            // IPv6 unique-local fc00::/7, transition/documentation ranges and mapped IPv4.
            if ((bytes[0] and 0xfe) == 0xfc) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && (bytes[3] and 0xf0) == 0x10) return false
            if (address.isIPv4CompatibleAddress) return false
            if (bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff) {
                val mapped = InetAddress.getByAddress(ByteArray(4) { bytes[bytes.size - 4 + it].toByte() })
                return isGloballyRoutable(mapped)
            }
        }
        return true
    }
}
