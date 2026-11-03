/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response

/** Reuses only the address answers already checked by the request-destination policy. */
class PinnedPublicDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        NetworkDestinationPolicy.pinnedPublicAddresses(hostname)
            ?: throw UnknownHostException("Host is not allowed by the network destination policy.")
}

/** Runs before DNS/socket creation for each initial application request. */
class SafeNetworkRequestInterceptor(
    private val allowPublicCrossOriginRedirects: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val scope = request.tag(NetworkDestinationPolicy.RequestScope::class.java)
            ?: NetworkDestinationPolicy.scopeFor(
                request.url.toString(),
                request.url.toString().takeIf(NetworkDestinationPolicy::isTrustedOrigin),
                allowPublicCrossOriginRedirects,
            )
        if (!NetworkDestinationPolicy.isAllowedHttpsRequest(request.url.toString(), scope)) {
            throw IOException("The network destination is not allowed.")
        }
        // Application interceptors run once for an OkHttp call; carrying the scope in the request
        // tag makes it available to every network-interceptor invocation during redirect handling.
        val scopedRequest = if (request.tag(NetworkDestinationPolicy.RequestScope::class.java) == null) {
            request.newBuilder()
                .tag(NetworkDestinationPolicy.RequestScope::class.java, scope)
                .build()
        } else {
            request
        }
        return NetworkDestinationPolicy.withDnsScope(scope) {
            chain.proceed(scopedRequest)
        }
    }
}

/** Validates redirects; cross-origin hops are only possible to public HTTPS for media clients. */
class SafeNetworkRedirectInterceptor(
    private val allowPublicCrossOriginRedirects: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val scope = request.tag(NetworkDestinationPolicy.RequestScope::class.java)
            ?: NetworkDestinationPolicy.scopeFor(
                request.url.toString(),
                request.url.toString().takeIf(NetworkDestinationPolicy::isTrustedOrigin),
                allowPublicCrossOriginRedirects,
            )
        if (!NetworkDestinationPolicy.isAllowedHttpsRequest(request.url.toString(), scope)) {
            throw IOException("The network destination is not allowed.")
        }
        val response = chain.proceed(request)
        if (!response.isRedirect) return response
        val location = response.header("Location")
        val destination = location?.let(request.url::resolve)
        val permitted = destination != null &&
            NetworkDestinationPolicy.isAllowedRedirect(destination.toString(), scope)
        if (!permitted) {
            response.close()
            throw IOException("The network redirect destination is not allowed.")
        }
        return response
    }
}
