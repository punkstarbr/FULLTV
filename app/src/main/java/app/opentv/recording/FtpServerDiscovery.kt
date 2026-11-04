/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A local FTP endpoint; only numeric private IPv4 addresses are exposed to the UI. */
data class FtpServerCandidate(
    val name: String,
    val host: String,
    val port: Int,
)

/**
 * Local-only FTP discovery. It combines DNS-SD advertisements with a bounded probe of TCP/21 in
 * the current private IPv4 subnet. The probe sends no credentials, caps parallel sockets, and never
 * scans outside the /24 containing this device (or the smaller actual subnet). FTP destinations
 * still pass FtpClient's RFC1918 validation before they can be saved or used.
 */
object FtpServerDiscovery {
    private const val FTP_SERVICE_TYPE = "_ftp._tcp."
    const val DEFAULT_SCAN_DURATION_MILLIS = 5_000L
    private const val MAX_SCAN_DURATION_MILLIS = 12_000L
    private const val PORT_SCAN_DEADLINE_MILLIS = 8_000L
    private const val PORT_SCAN_CONNECT_TIMEOUT_MILLIS = 350
    private const val PORT_SCAN_READ_TIMEOUT_MILLIS = 350
    private const val PORT_SCAN_MAX_PARALLEL = 32
    private const val FTP_CONTROL_PORT = 21
    private const val MAX_BANNER_BYTES = 256

    /** Return the default gateway only when the active transport is Wi-Fi or Ethernet. */
    fun currentPrivateGatewayIpv4(context: Context): String? {
        val link = activeLanLinkProperties(context) ?: return null
        val gateway = link.routes.firstOrNull { route ->
            route.isDefaultRoute && route.gateway is Inet4Address
        }?.gateway?.hostAddress ?: return null
        return gateway.takeIf(FtpClient::isPrivateRfc1918Ipv4)
    }

    /**
     * Find locally advertised FTP services and servers answering on the standard FTP port 21.
     * DNS-SD can report custom ports; the bounded subnet probe checks port 21 only. No login or
     * file operation is attempted. Results are limited to private IPv4 addresses on the active LAN.
     */
    suspend fun discover(
        context: Context,
        durationMillis: Long = DEFAULT_SCAN_DURATION_MILLIS,
    ): List<FtpServerCandidate> = coroutineScope {
        require(durationMillis in 1_000L..MAX_SCAN_DURATION_MILLIS) {
            "FTP discovery duration must be between 1 and 12 seconds."
        }
        val advertised = async {
            try {
                discoverMdns(context, durationMillis)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
        val standardPort = async { scanPrivateSubnetForFtp(context) }
        (advertised.await() + standardPort.await()).distinctBy { "${it.host}:${it.port}" }
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

    /** Build a bounded host list from the /24 containing [address], or a smaller real subnet. */
    internal fun privateIpv4ScanHosts(address: InetAddress?, prefixLength: Int): List<String> {
        val ipv4 = address as? Inet4Address ?: return emptyList()
        val ownAddress = ipv4.hostAddress ?: return emptyList()
        if (!FtpClient.isPrivateRfc1918Ipv4(ownAddress) || prefixLength !in 1..32) return emptyList()

        // Large home /16 networks are intentionally capped to the /24 containing this device.
        // Smaller networks are not expanded beyond their actual subnet.
        val scanPrefix = maxOf(prefixLength, 24)
        val hostBits = 32 - scanPrefix
        if (hostBits <= 1) return emptyList()
        val ipValue = ipv4.address.fold(0L) { value, octet ->
            (value shl 8) or (octet.toLong() and 0xffL)
        }
        val addressMask = (0xffff_ffffL shl hostBits) and 0xffff_ffffL
        val network = ipValue and addressMask
        val broadcast = network or ((1L shl hostBits) - 1L)
        return (network + 1 until broadcast)
            .asSequence()
            .map(::formatIpv4)
            .filter { it != ownAddress && FtpClient.isPrivateRfc1918Ipv4(it) }
            .toList()
    }

    internal fun isFtpWelcomeBanner(line: String?): Boolean =
        line != null && line.startsWith("220") && (line.length == 3 || line[3] == ' ' || line[3] == '-')

    private suspend fun discoverMdns(
        context: Context,
        durationMillis: Long,
    ): List<FtpServerCandidate> = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val manager = context.getSystemService(NsdManager::class.java)
            if (manager == null) {
                continuation.resumeWithException(IllegalStateException("Local service discovery is unavailable."))
            } else {
                DiscoverySession(manager, durationMillis, continuation).start()
            }
        }
    }

    private suspend fun scanPrivateSubnetForFtp(context: Context): List<FtpServerCandidate> {
        val targets = withContext(Dispatchers.IO) {
            val link = activeLanLinkProperties(context) ?: return@withContext emptyList()
            val local = link.linkAddresses.firstOrNull { address ->
                address.address is Inet4Address &&
                    FtpClient.isPrivateRfc1918Ipv4(address.address.hostAddress.orEmpty())
            } ?: return@withContext emptyList()
            privateIpv4ScanHosts(local.address, local.prefixLength)
        }
        if (targets.isEmpty()) return emptyList()

        return withTimeoutOrNull(PORT_SCAN_DEADLINE_MILLIS) {
            coroutineScope {
                val permits = Semaphore(PORT_SCAN_MAX_PARALLEL)
                targets.map { host ->
                    async(Dispatchers.IO) {
                        permits.withPermit { probeFtpServer(host) }
                    }
                }.awaitAll().filterNotNull()
            }
        } ?: emptyList()
    }

    private fun activeLanLinkProperties(context: Context): LinkProperties? = try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val onLan = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (onLan) connectivity.getLinkProperties(network) else null
    } catch (_: Exception) {
        null
    }

    private fun probeFtpServer(host: String): FtpServerCandidate? {
        if (!FtpClient.isPrivateRfc1918Ipv4(host)) return null
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(host, FTP_CONTROL_PORT),
                    PORT_SCAN_CONNECT_TIMEOUT_MILLIS,
                )
                socket.soTimeout = PORT_SCAN_READ_TIMEOUT_MILLIS
                if (!isFtpWelcomeBanner(readBoundedLine(socket))) return null
                FtpServerCandidate(name = host, host = host, port = FTP_CONTROL_PORT)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readBoundedLine(socket: Socket): String? {
        val bytes = ByteArray(MAX_BANNER_BYTES)
        var length = 0
        while (length < bytes.size) {
            val next = socket.getInputStream().read()
            if (next < 0 || next == '\n'.code) break
            if (next != '\r'.code) bytes[length++] = next.toByte()
        }
        return if (length == 0) null else String(bytes, 0, length, Charsets.US_ASCII)
    }

    private fun formatIpv4(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 0xffL).toString() }

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
            if (completed.get() || serviceInfo.serviceType?.equals(FTP_SERVICE_TYPE, ignoreCase = true) != true) return
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
