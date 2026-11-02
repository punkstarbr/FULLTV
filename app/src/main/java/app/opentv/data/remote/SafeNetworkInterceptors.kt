/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import java.io.IOException
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response

/** Reuses the public IP answers already checked for remote catalogue destinations. */
class PinnedPublicDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        NetworkDestinationPolicy.pinnedPublicAddresses(hostname) ?: Dns.SYSTEM.lookup(hostname)
}

/** Runs before DNS/socket creation for each initial application request. */
class SafeNetworkRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!NetworkDestinationPolicy.isAllowedHttpsRequest(request.url.toString())) {
            throw IOException("The network destination is not allowed.")
        }
        return chain.proceed(request)
    }
}

/** Validates redirects before OkHttp follows them, so HTTPS cannot silently downgrade or reach LAN targets. */
class SafeNetworkRedirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!NetworkDestinationPolicy.isAllowedHttpsRequest(request.url.toString())) {
            throw IOException("The network destination is not allowed.")
        }
        val response = chain.proceed(request)
        if (!response.isRedirect) return response
        val location = response.header("Location")
        val destination = location?.let(request.url::resolve)
        val permitted = destination != null &&
            NetworkDestinationPolicy.isAllowedHttpsRequest(destination.toString())
        if (!permitted) {
            response.close()
            throw IOException("The network redirect destination is not allowed.")
        }
        return response
    }
}
