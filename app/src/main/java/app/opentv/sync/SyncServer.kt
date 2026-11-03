/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import android.util.Log
import app.opentv.pairing.PairingHttp
import app.opentv.security.LanConnectionLimiter
import app.opentv.security.LanTlsServer
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Serves this device's watch-history bundle to another OpenTV on the same wifi.
 *
 * Deliberately the same shape as [app.opentv.pairing.PairingServer]: no cloud, a short-lived
 * server on the LAN, gated by a six-digit code shown on screen, private-network only, and torn
 * down on a timeout. The bundle is a snapshot taken when sharing starts, served as-is.
 */
class SyncServer(private val scope: CoroutineScope) {

    data class Session(
        val host: String,
        val port: Int,
        val code: String,
        /** 96-bit SHA-256 SPKI pin; compare and type it on the receiving FullTV device. */
        val syncPin: String,
        /** Full certificate fingerprint for out-of-band verification if needed. */
        val certificateSha256: String,
    ) {
        val address: String get() = "https://$host:$port"
    }

    sealed interface State {
        data object Idle : State
        data class Sharing(val session: Session) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var serverSocket: LanTlsServer.Listener? = null
    private var code: String = ""
    private var bundle: String = ""
    private val wrongAttempts = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var running = false
    @Volatile private var starting = false
    @Volatile private var generation = 0
    private var startJob: Job? = null
    private val admission = LanConnectionLimiter()

    @Synchronized
    fun start(bundleJson: String) {
        if (running || starting) return
        if (bundleJson.toByteArray(Charsets.UTF_8).size > MAX_BUNDLE_BYTES) {
            _state.value = State.Failed("This sync bundle is too large to transfer securely.")
            return
        }
        starting = true
        val runGeneration = ++generation
        startJob = scope.launch(Dispatchers.IO) {
            var listener: LanTlsServer.Listener? = null
            try {
                val host = lanAddress() ?: run {
                    _state.value = State.Failed("This device isn't on a network, so it can't share.")
                    return@launch
                }
                val random = SecureRandom()
                val nextCode = "%06d".format(random.nextInt(1_000_000))
                val opened = LanTlsServer.open(host)
                listener = opened
                val installed = synchronized(this@SyncServer) {
                    if (runGeneration != generation) {
                        false
                    } else {
                        code = nextCode
                        bundle = bundleJson
                        wrongAttempts.set(0)
                        serverSocket = opened
                        running = true
                        admission.reset()
                        true
                    }
                }
                if (!installed) return@launch
                _state.value = State.Sharing(
                    Session(
                        host = opened.host,
                        port = opened.port,
                        code = code,
                        syncPin = opened.syncPin,
                        certificateSha256 = LanTlsServer.formatCertificateFingerprint(opened.certificateSha256),
                    ),
                )

                thread(name = "opentv-sync-tls", isDaemon = true) {
                    while (running && runGeneration == generation) {
                        val client = try {
                            opened.acceptClient(SOCKET_TIMEOUT_MILLIS)
                        } catch (error: Exception) {
                            if (!running || runGeneration != generation) break
                            Log.w(TAG, "Rejected sync TLS connection (${error.javaClass.simpleName})")
                            continue
                        }
                        if (!running || runGeneration != generation) {
                            runCatching { client.close() }
                            break
                        }
                        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
                            runCatching { client.close() }
                            continue
                        }
                        val permit = admission.tryAcquire(client.inetAddress?.hostAddress)
                        if (permit == null) {
                            runCatching { client.close() }
                            continue
                        }
                        thread(name = "opentv-sync-request", isDaemon = true) {
                            try {
                                opened.completeHandshake(client)
                                if (running && runGeneration == generation) {
                                    LanTlsServer.closeAfter(client, RESPONSE_DEADLINE_MILLIS).use {
                                        handle(client, "${opened.host}:${opened.port}")
                                    }
                                }
                            } catch (error: Exception) {
                                Log.w(TAG, "Sync request failed (${error.javaClass.simpleName})")
                            } finally {
                                runCatching { client.close() }
                                permit.close()
                            }
                        }
                    }
                }
                listener = null // ownership transferred to stop()/the accept loop

                scope.launch(Dispatchers.Default) {
                    delay(TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES))
                    if (running && runGeneration == generation) {
                        stop()
                        _state.value = State.Failed("Sharing timed out. Start again when you're ready.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not start TLS sync (${error.javaClass.simpleName})")
                if (runGeneration == generation) {
                    _state.value = State.Failed("Could not start secure sharing on this device.")
                }
            } finally {
                runCatching { listener?.close() }
                if (runGeneration == generation) starting = false
            }
        }
    }

    @Synchronized
    fun stop() {
        generation++
        starting = false
        startJob?.cancel()
        startJob = null
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        code = ""
        bundle = ""
        if (_state.value is State.Sharing) _state.value = State.Idle
    }

    private fun handle(client: Socket, expectedAuthority: String) {
        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
            client.getOutputStream().write(
                PairingHttp.httpResponse("403 Forbidden", "text/plain", "Local network only."),
            )
            return
        }
        val output = client.getOutputStream()
        val request = try {
            PairingHttp.readRequest(client.getInputStream(), maxBodyBytes = 0)
        } catch (error: PairingHttp.RequestException) {
            output.write(PairingHttp.httpResponse(error.status, "text/plain", "Invalid request."))
            return
        } ?: return
        if (!request.headers["host"].equals(expectedAuthority, ignoreCase = true)) {
            output.write(PairingHttp.httpResponse("421 Misdirected Request", "text/plain", "Wrong local host."))
            return
        }
        if (request.headers.containsKey("origin") || request.headers["x-fulltv-sync"] != "1") {
            output.write(PairingHttp.httpResponse("403 Forbidden", "text/plain", "Browser requests are not accepted."))
            return
        }
        val supplied = request.query["c"].orEmpty()

        if (request.method != "GET" || request.path != "/export") {
            output.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
            return
        }
        if (code.isEmpty() || !supplied.matches(Regex("\\d{6}")) || !PairingHttp.secretsMatch(supplied, code)) {
            val attempts = wrongAttempts.incrementAndGet()
            if (attempts >= MAX_ATTEMPTS) {
                output.write(PairingHttp.httpResponse("403 Forbidden", "text/plain", "Too many attempts."))
                stop()
                _state.value = State.Failed("Too many incorrect codes. Sharing cancelled.")
                return
            }
            output.write(PairingHttp.httpResponse("401 Unauthorized", "text/plain", "Wrong code."))
            return
        }
        output.write(PairingHttp.httpResponse("200 OK", "application/json", bundle))
    }

    private fun lanAddress(): String? {
        val candidates = mutableListOf<InetAddress>()
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    if (address.isLoopbackAddress) continue
                    if (address.hostAddress?.contains(':') == true) continue // IPv4 only
                    if (PairingHttp.isPrivateAddress(address.hostAddress)) candidates += address
                }
            }
        }
        return candidates
            .sortedBy { it.hostAddress?.startsWith("169.254") == true }
            .firstOrNull()
            ?.hostAddress
    }

    private companion object {
        const val TAG = "OpenTV"
        const val TIMEOUT_MINUTES = 5L
        const val SOCKET_TIMEOUT_MILLIS = 5_000
        const val RESPONSE_DEADLINE_MILLIS = 10_000L
        const val MAX_ATTEMPTS = 5
        const val MAX_BUNDLE_BYTES = 8 * 1024 * 1024
    }
}
