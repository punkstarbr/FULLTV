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
 * Filters network destinations supplied by remote catalogues/add-ons.
 *
 * A [RequestScope] is attached to every application-owned request. It records the origin at which
 * the operation started and, when the user configured a private source, the one exact origin which
 * may use private DNS/IP answers. A media client may additionally follow redirects to public HTTPS
 * destinations; private destinations are still limited to the exact configured origin.
 *
 * The small trusted-origin set remains only for transports which cannot carry an OkHttp Request tag
 * (Media3's player requests). It is reconciled from the source table and is never consulted to
 * authorise a redirect. DNS answers are cached as the complete validated answer set and are never
 * replaced by an arbitrary system-DNS lookup after validation expires.
 */
object NetworkDestinationPolicy {
    private const val CACHE_TTL_MILLIS = 60_000L
    private const val MAX_CACHE_ENTRIES = 512
    private const val MAX_TRUSTED_ORIGINS = 512
    private val resolvedHosts = ConcurrentHashMap<String, CachedHost>()
    private val trustedOrigins = ConcurrentHashMap.newKeySet<String>()
    private val cacheLock = Any()
    private val trustedLock = Any()
    private val dnsScope = ThreadLocal<RequestScope?>()

    private data class CachedHost(
        val addresses: List<InetAddress>,
        val expiresAt: Long,
    )

    /** The authorization context of one initial network operation. */
    data class RequestScope(
        val initialOrigin: String,
        val trustedOrigin: String?,
        val allowPublicCrossOriginRedirects: Boolean = false,
    )

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
        val authorityHost = if (host.contains(':')) "[$host]" else host
        return "https://$authorityHost:$port"
    }

    /** Builds a request context. A private exception is valid only for the initial origin itself. */
    fun scopeFor(
        initialUrl: String,
        trustedOriginUrl: String? = null,
        allowPublicCrossOriginRedirects: Boolean = false,
    ): RequestScope? {
        val initialOrigin = originOf(initialUrl) ?: return null
        val trustedOrigin = trustedOriginUrl?.let(::originOf) ?: if (trustedOriginUrl == null) null else return null
        if (trustedOrigin != null && trustedOrigin != initialOrigin) return null
        return RequestScope(initialOrigin, trustedOrigin, allowPublicCrossOriginRedirects)
    }

    /** Call only for an origin that the user explicitly configured or approved in the app. */
    fun registerTrustedOrigin(raw: String): Boolean {
        val origin = originOf(raw) ?: return false
        val host = hostOf(raw) ?: return false
        synchronized(trustedLock) {
            if (origin !in trustedOrigins && trustedOrigins.size >= MAX_TRUSTED_ORIGINS) return false
            trustedOrigins += origin
        }
        // A host may previously have been cached as public-only. Revalidate it under the new
        // configured-origin policy rather than silently reusing a less permissive answer forever.
        resolvedHosts.remove(host)
        return true
    }

    /** Revokes a configured origin after a source is removed or its URL is replaced. */
    fun revokeTrustedOrigin(raw: String): Boolean {
        val origin = originOf(raw) ?: return false
        val host = hostOf(raw) ?: return false
        val removed = synchronized(trustedLock) {
            trustedOrigins.remove(origin)
        }
        if (removed) resolvedHosts.remove(host)
        return removed
    }

    /** Replaces the process-local compatibility set with the currently configured source origins. */
    fun replaceTrustedOrigins(rawUrls: Iterable<String>) {
        val origins = rawUrls.mapNotNull(::originOf).toSet()
        synchronized(trustedLock) {
            trustedOrigins.clear()
            trustedOrigins.addAll(origins.take(MAX_TRUSTED_ORIGINS))
        }
        // The validation mode for one of these hosts may have changed in either direction.
        resolvedHosts.clear()
    }

    fun isTrustedOrigin(raw: String): Boolean = originOf(raw)?.let(trustedOrigins::contains) == true

    /** Compatibility path for transports without request tags, such as Media3. */
    fun isAllowedHttpsRequest(raw: String): Boolean {
        val trusted = originOf(raw)?.takeIf(trustedOrigins::contains)?.let { raw }
        return isAllowedHttps(raw, trusted)
    }

    /** Validates an OkHttp request against its operation scope. */
    fun isAllowedHttpsRequest(raw: String, scope: RequestScope?): Boolean {
        val origin = originOf(raw) ?: return false
        if (scope == null) return isAllowedHttps(raw)
        if (origin == scope.initialOrigin) return isAllowedHttps(raw, scope.trustedOrigin)
        // Cross-origin redirects are never authorized by another user's configured private origin.
        // Media requests can follow public HTTPS CDNs without opening a path to private networks.
        return scope.allowPublicCrossOriginRedirects && isAllowedHttps(raw)
    }

    /** A cross-origin redirect, when enabled, must resolve exclusively to public Internet IPs. */
    fun isAllowedRedirect(raw: String, scope: RequestScope?): Boolean =
        isAllowedHttpsRequest(raw, scope)

    /** Keeps the exact request scope visible to OkHttp's synchronous DNS callback. */
    internal fun <T> withDnsScope(scope: RequestScope?, block: () -> T): T {
        val previous = dnsScope.get()
        dnsScope.set(scope)
        return try {
            block()
        } finally {
            dnsScope.set(previous)
        }
    }

    /**
     * Validates HTTPS and DNS. When [trustedOriginUrl] has the exact same origin as [raw], private
     * answers are permitted because that origin was explicitly supplied by the user for this call.
     * The answer is still resolved and pinned; no private destination bypasses DNS validation.
     */
    fun isAllowedHttps(raw: String, trustedOriginUrl: String? = null): Boolean {
        val origin = originOf(raw) ?: return false
        val host = hostOf(raw) ?: return false
        val trustedOrigin = trustedOriginUrl?.let(::originOf)
        val allowPrivate = trustedOrigin != null && origin == trustedOrigin
        return validatedAddresses(host, allowPrivate) != null
    }

    fun isPublicInternetHost(rawHost: String): Boolean = publicAddresses(rawHost) != null

    /**
     * Return previously validated addresses for OkHttp DNS. Unlike Dns.SYSTEM, this never falls back
     * to a fresh arbitrary answer after a validation failure or a DNS rebinding event.
     */
    fun pinnedPublicAddresses(rawHost: String): List<InetAddress>? {
        val host = normalizeHost(rawHost) ?: return null
        // Trust is request-scoped, never inherited from another configured source on this host.
        // This matters when a hostname is re-used on a different port or reached through a redirect.
        val allowPrivate = dnsScope.get()?.trustedOrigin?.let(::hostOf) == host
        val entry = resolvedHosts[host]
        val now = System.currentTimeMillis()
        if (entry != null && now < entry.expiresAt) {
            val addresses = entry.addresses.takeIf {
                it.isNotEmpty() && (allowPrivate || it.all(::isGloballyRoutable))
            }
            if (addresses != null) return addresses
            throw UnknownHostException("Host is not allowed by the network destination policy.")
        }
        return validatedAddresses(host, allowPrivate)
            ?: throw UnknownHostException("Host is not allowed by the network destination policy.")
    }

    private fun publicAddresses(rawHost: String): List<InetAddress>? =
        validatedAddresses(rawHost, allowPrivate = false)

    private fun validatedAddresses(rawHost: String, allowPrivate: Boolean): List<InetAddress>? {
        val host = normalizeHost(rawHost) ?: return null
        if (isLocalName(host)) return null
        val now = System.currentTimeMillis()
        resolvedHosts[host]?.let { cached ->
            if (now < cached.expiresAt) {
                return cached.addresses.takeIf {
                    it.isNotEmpty() && (allowPrivate || it.all(::isGloballyRoutable))
                }
            }
            resolvedHosts.remove(host, cached)
        }

        val addresses = runCatching { InetAddress.getAllByName(host).toList() }
            .getOrNull().orEmpty()
        val accepted = addresses.takeIf {
            it.isNotEmpty() && (allowPrivate || it.all(::isGloballyRoutable))
        }
        synchronized(cacheLock) {
            val currentTime = System.currentTimeMillis()
            resolvedHosts.entries.removeIf { currentTime >= it.value.expiresAt }
            if (resolvedHosts.size >= MAX_CACHE_ENTRIES && !resolvedHosts.containsKey(host)) return null
            // Keep the complete DNS answer, even when it is rejected for an untrusted operation, so
            // a later pinned lookup cannot accidentally use an answer that was never checked.
            resolvedHosts[host] = CachedHost(addresses, currentTime + CACHE_TTL_MILLIS)
        }
        return accepted
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
            // IPv6 unique-local fc00::/7, NAT64, 6to4/Teredo, documentation and mapped IPv4.
            if ((bytes[0] and 0xfe) == 0xfc) return false
            if (bytes[0] == 0x00 && bytes[1] == 0x64 && bytes[2] == 0xff && bytes[3] == 0x9b &&
                ((4..11).all { bytes[it] == 0 } || (bytes[4] == 0 && bytes[5] == 1))
            ) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x02) return false
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) return false
            val secondHextet = (bytes[2] shl 8) or bytes[3]
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && secondHextet <= 0x01ff) return false
            if (address.isIPv4CompatibleAddress) return false
            if (bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff) {
                val mapped = InetAddress.getByAddress(ByteArray(4) { bytes[bytes.size - 4 + it].toByte() })
                return isGloballyRoutable(mapped)
            }
        }
        return true
    }
}
