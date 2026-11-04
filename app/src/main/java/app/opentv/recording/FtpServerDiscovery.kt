/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A local FTP endpoint advertised through DNS-SD; only a numeric private IPv4 is exposed to the UI. */
data class FtpServerCandidate(
    val name: String,
    val host: String,
    val port: Int,
)

/**
 * Best-effort discovery for devices that advertise `_ftp._tcp` with mDNS, plus the local router
 * gateway shortcut. No subnet port scan is performed. FTP destinations still pass FtpClient's
 * RFC1918, numeric-address validation before they can be saved or used.
 */
object FtpServerDiscovery {
    private const val FTP_SERVICE_TYPE = "_ftp._tcp."
    const val DEFAULT_SCAN_DURATION_MILLIS = 5_000L
    private const val MAX_SCAN_DURATION_MILLIS = 12_000L

    /** Return the default gateway only when the active transport is Wi-Fi or Ethernet. */
    fun currentPrivateGatewayIpv4(context: Context): String? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val onLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!onLan) return null
        val link = connectivity.getLinkProperties(network) ?: return null
        val gateway = link.routes.firstOrNull { route ->
            route.isDefaultRoute && route.gateway is Inet4Address
        }?.gateway?.hostAddress ?: return null
        gateway.takeIf(FtpClient::isPrivateRfc1918Ipv4)
    }.getOrNull()

    /**
     * Discover FTP services on the current LAN for a bounded period. Only RFC1918 IPv4 endpoints
     * are returned, even if a service advertises a public address, IPv6 address, or invalid port.
     */
    suspend fun discover(
        context: Context,
        durationMillis: Long = DEFAULT_SCAN_DURATION_MILLIS,
    ): List<FtpServerCandidate> {
        require(durationMillis in 1_000L..MAX_SCAN_DURATION_MILLIS) {
            "FTP discovery duration must be between 1 and 12 seconds."
        }
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val manager = context.getSystemService(NsdManager::class.java)
                if (manager == null) {
                    continuation.resumeWithException(IllegalStateException("Local service discovery is unavailable."))
                } else {
                    DiscoverySession(manager, durationMillis, continuation).start()
                }
            }
        }
    }

    internal fun candidateForResolvedHost(
        serviceName: String,
        address: InetAddress?,
        port: Int,
    ): FtpServerCandidate? {
        val host = (address as? Inet4Address)?.hostAddress ?: return null
        if (!FtpClient.isPrivateRfc1918Ipv4(host) || port !in 1..65_535) return null
        val safeName = serviceName
            .filterNot(Char::isISOControl)
            .take(80)
            .trim()
            .ifBlank { host }
        return FtpServerCandidate(safeName, host, port)
    }

    private class DiscoverySession(
        private val manager: NsdManager,
        private val durationMillis: Long,
        private val continuation: CancellableContinuation<List<FtpServerCandidate>>,
    ) : NsdManager.DiscoveryListener {
        private val handler = Handler(Looper.getMainLooper())
        private val completed = AtomicBoolean(false)
        private val candidates = LinkedHashMap<String, FtpServerCandidate>()
        private var discoveryStarted = false

        private val timeout = Runnable { finish() }

        fun start() {
            continuation.invokeOnCancellation { handler.post { finish() } }
            handler.postDelayed(timeout, durationMillis)
            try {
                discoveryStarted = true
                @Suppress("DEPRECATION")
                manager.discoverServices(FTP_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, this)
            } catch (error: Exception) {
                finish(error)
            }
        }

        override fun onDiscoveryStarted(serviceType: String) {
            discoveryStarted = true
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (completed.get() || !serviceInfo.serviceType.equals(FTP_SERVICE_TYPE, ignoreCase = true)) return
            try {
                @Suppress("DEPRECATION")
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (completed.get()) return
                        val candidate = candidateForResolvedHost(
                            serviceName = resolved.serviceName.orEmpty(),
                            address = resolved.host,
                            port = resolved.port,
                        ) ?: return
                        candidates["${candidate.host}:${candidate.port}"] = candidate
                    }
                })
            } catch (_: Exception) {
                // Keep scanning; another advertised endpoint may still resolve successfully.
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            candidates.entries.removeAll { (_, candidate) -> candidate.name == serviceInfo.serviceName }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            discoveryStarted = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            finish(IOException("FTP discovery could not start (error $errorCode)."))
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryStarted = false
        }

        private fun finish(error: Throwable? = null) {
            if (!completed.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            if (discoveryStarted) {
                discoveryStarted = false
                runCatching { manager.stopServiceDiscovery(this) }
            }
            if (!continuation.isActive) return
            if (error != null) continuation.resumeWithException(error)
            else continuation.resume(candidates.values.toList())
        }
    }
}
