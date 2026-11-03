/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.util.Log
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lets someone fill in their provider details from a phone instead of a TV remote.
 *
 * ## Why the TV is the server
 *
 * The obvious way to build "scan a code, type on your phone, it appears on the telly" is a
 * website: the TV registers a code with a server, the phone posts to the same server, the TV
 * polls for it. Every streaming app does it that way.
 *
 * It is the wrong choice here. That server has to be paid for every month forever, and it
 * would hold IPTV credentials for every user of the app. It is precisely the dependency that
 * means an app dies when its maintainer loses interest — which is the failure this whole
 * project exists in response to.
 *
 * So there is no server. The TV runs one, on the local network, for the couple of minutes the
 * pairing screen is open. Credentials go phone → TV over the user's own wifi and never leave
 * the building. It works with the internet unplugged.
 *
 * ## What stops the neighbours using it
 *
 * - It binds to the LAN on a **random high port**, and only while the pairing screen is open.
 * - The URL carries a **192-bit random token**. Knowing the IP and port is not enough.
 * - The phone must also enter a **six-digit code shown on the TV**. That is the part the user
 *   sees, and it is what defends against someone who somehow obtained the link.
 * - Requests from non-private addresses are refused outright.
 * - After [SESSION_TIMEOUT_MINUTES], or on success, the socket closes and the token is void.
 * - Five wrong codes and the session is burned.
 */
class PairingServer(private val scope: CoroutineScope) {

    data class Session(
        /** What the QR code encodes, e.g. `https://192.168.0.14:41234/?t=…` */
        val url: String,
        /** Shown on the TV, typed on the phone. */
        val confirmCode: String,
        val host: String,
        val port: Int,
        /** SHA-256 of the DER certificate, for out-of-band browser verification. */
        val certificateSha256: String,
    ) {
        /** Full fallback URL: host:port alone does not include the one-time pairing token. */
        val shortUrl: String get() = url
    }

    sealed interface State {
        data object Idle : State
        data class Listening(val session: Session) : State
        /** The phone submitted details. The UI takes it from here. */
        data class Received(val draft: Source) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var serverSocket: LanTlsServer.Listener? = null
    private var token: String = ""
    private var confirmCode: String = ""
    private val wrongCodeAttempts = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var running = false
    @Volatile private var starting = false
    @Volatile private var generation = 0
    private var startJob: Job? = null
    private val admission = LanConnectionLimiter()

    @Synchronized
    fun start() {
        if (running || starting) return
        starting = true
        val runGeneration = ++generation
        startJob = scope.launch(Dispatchers.IO) {
            var listener: LanTlsServer.Listener? = null
            try {
                val host = lanAddress() ?: run {
                    _state.value = State.Failed(
                        "This device doesn't appear to be on a network, so it can't talk to your phone.",
                    )
                    return@launch
                }
                val random = SecureRandom()
                val nextToken = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                val nextCode = "%06d".format(random.nextInt(1_000_000))
                val opened = LanTlsServer.open(host)
                listener = opened
                val installed = synchronized(this@PairingServer) {
                    if (runGeneration != generation) {
                        false
                    } else {
                        token = nextToken
                        confirmCode = nextCode
                        wrongCodeAttempts.set(0)
                        serverSocket = opened
                        running = true
                        admission.reset()
                        true
                    }
                }
                if (!installed) return@launch
                val activeListener = opened
                val session = Session(
                    url = "https://${activeListener.host}:${activeListener.port}/?t=$token",
                    confirmCode = confirmCode,
                    host = activeListener.host,
                    port = activeListener.port,
                    certificateSha256 = LanTlsServer.formatCertificateFingerprint(activeListener.certificateSha256),
                )
                _state.value = State.Listening(session)

                // TLS handshake happens before the HTTP handler sees or reads any credentials.
                thread(name = "opentv-pairing-tls", isDaemon = true) {
                    while (running && runGeneration == generation) {
                        val client = try {
                            activeListener.acceptClient(SOCKET_TIMEOUT_MILLIS)
                        } catch (error: Exception) {
                            if (!running || runGeneration != generation) break
                            Log.w(TAG, "Rejected pairing TLS connection (${error.javaClass.simpleName})")
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
                        thread(name = "opentv-pairing-request", isDaemon = true) {
                            try {
                                activeListener.completeHandshake(client)
                                if (running && runGeneration == generation) {
                                    LanTlsServer.closeAfter(client, RESPONSE_DEADLINE_MILLIS).use {
                                        handle(client, "${activeListener.host}:${activeListener.port}")
                                    }
                                }
                            } catch (error: Exception) {
                                Log.w(TAG, "Pairing request failed (${error.javaClass.simpleName})")
                            } finally {
                                runCatching { client.close() }
                                permit.close()
                            }
                        }
                    }
                }
                listener = null // ownership transferred to stop()/the accept loop

                scope.launch(Dispatchers.Default) {
                    kotlinx.coroutines.delay(TimeUnit.MINUTES.toMillis(SESSION_TIMEOUT_MINUTES))
                    if (running && runGeneration == generation && _state.value is State.Listening) {
                        stop()
                        _state.value = State.Failed("Pairing timed out. Start again when you're ready.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not start TLS pairing (${error.javaClass.simpleName})")
                if (runGeneration == generation) {
                    _state.value = State.Failed("Could not start secure pairing on this device.")
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
        token = ""
        confirmCode = ""
        if (_state.value is State.Listening) _state.value = State.Idle
    }

    // ---- Request handling ------------------------------------------------------------------

    private fun handle(client: Socket, expectedAuthority: String) {
        if (!PairingHttp.isPrivateAddress(client.inetAddress?.hostAddress)) {
            client.getOutputStream().write(
                PairingHttp.httpResponse("403 Forbidden", "text/plain", "Local network only."),
            )
            return
        }
        val output = client.getOutputStream()
        val request = try {
            PairingHttp.readRequest(client.getInputStream(), MAX_BODY_BYTES)
        } catch (error: PairingHttp.RequestException) {
            output.write(PairingHttp.httpResponse(error.status, "text/plain", "Invalid or oversized request."))
            return
        } ?: return
        if (!request.headers["host"].equals(expectedAuthority, ignoreCase = true)) {
            output.write(PairingHttp.httpResponse("421 Misdirected Request", "text/plain", "Wrong local host."))
            return
        }
        if (request.method == "POST" &&
            !PairingHttp.isAllowedPairingOrigin(request.headers["origin"], expectedAuthority)
        ) {
            output.write(PairingHttp.httpResponse("403 Forbidden", "text/plain", "Cross-origin submission refused."))
            return
        }
        if (request.method == "POST" && request.headers["content-type"]
                ?.substringBefore(';')?.trim()?.equals("application/x-www-form-urlencoded", ignoreCase = true) != true
        ) {
            output.write(PairingHttp.httpResponse("415 Unsupported Media Type", "text/plain", "Form data required."))
            return
        }
        val form = PairingHttp.parseFormEncodedStrict(request.body)
        if (form == null) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Invalid form data."))
            return
        }

        // Every route checks the token. Without it the page does not exist.
        val supplied = request.query["t"] ?: form["t"] ?: ""
        if (token.isEmpty() || !PairingHttp.secretsMatch(supplied, token)) {
            output.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
            return
        }

        when {
            request.method == "GET" && request.path == "/" -> output.write(
                PairingHttp.httpResponse("200 OK", "text/html", PairingPage.form(token, null)),
            )

            request.method == "POST" && request.path == "/submit" -> handleSubmit(output, form)

            else -> output.write(
                PairingHttp.httpResponse("405 Method Not Allowed", "text/plain", "No."),
            )
        }
    }

    private fun handleSubmit(output: java.io.OutputStream, form: Map<String, String>) {
        val code = form["code"].orEmpty()
        if (!code.matches(Regex("\\d{6}")) || !PairingHttp.secretsMatch(code, confirmCode)) {
            val attempts = wrongCodeAttempts.incrementAndGet()
            if (attempts >= MAX_CODE_ATTEMPTS) {
                output.write(
                    PairingHttp.httpResponse(
                        "403 Forbidden",
                        "text/html",
                        PairingPage.message(
                            "Too many attempts",
                            "Start pairing again on the TV to get a new code.",
                        ),
                    ),
                )
                stop()
                _state.value = State.Failed("Too many incorrect codes. Pairing cancelled.")
                return
            }
            output.write(
                PairingHttp.httpResponse(
                    "200 OK",
                    "text/html",
                        PairingPage.form(
                            token,
                            "That code doesn't match the one on the TV. " +
                            "${MAX_CODE_ATTEMPTS - attempts} attempts left.",
                    ),
                ),
            )
            return
        }

        val kind = if (form["kind"] == "m3u") SourceKind.M3U else SourceKind.XTREAM
        val url = form["url"].orEmpty().trim()
        if (url.isEmpty()) {
            output.write(
                PairingHttp.httpResponse(
                    "200 OK",
                    "text/html",
                    PairingPage.form(token, "Please fill in the server address."),
                ),
            )
            return
        }

        val draft = Source(
            name = form["name"].orEmpty().trim().ifBlank {
                if (kind == SourceKind.XTREAM) "My provider" else "My playlist"
            },
            kind = kind,
            url = url,
            username = form["username"]?.trim()?.takeIf { it.isNotEmpty() },
            password = form["password"]?.takeIf { it.isNotEmpty() },
            epgUrl = form["epg"]?.trim()?.takeIf { it.isNotEmpty() },
        )

        output.write(
            PairingHttp.httpResponse(
                "200 OK",
                "text/html",
                PairingPage.message(
                    "Sent to your TV",
                    "You can put your phone down — the rest happens on the telly.",
                ),
            ),
        )
        output.flush()

        _state.value = State.Received(draft)
        // Nothing more to receive. Shut the door immediately rather than leaving a socket
        // open on the user's network for the rest of the timeout.
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        token = ""
    }

    // ---- Finding our own address -----------------------------------------------------------

    /**
     * The device's address on the LAN.
     *
     * Enumerating interfaces rather than asking ConnectivityManager, because TV boxes are
     * frequently on ethernet, sometimes on both, and the wifi-shaped API returns nothing
     * useful when the box is wired.
     */
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
        }.onFailure { Log.w(TAG, "Could not enumerate interfaces", it) }

        // Prefer a real private range over a 169.254 self-assigned address, which means
        // DHCP failed and nothing will be able to reach us anyway.
        return candidates
            .sortedBy { it.hostAddress?.startsWith("169.254") == true }
            .firstOrNull()
            ?.hostAddress
    }

    private companion object {
        const val TAG = "PairingServer"
        const val SESSION_TIMEOUT_MINUTES = 5L
        const val SOCKET_TIMEOUT_MILLIS = 5_000
        const val RESPONSE_DEADLINE_MILLIS = 8_000L
        const val MAX_BODY_BYTES = 16 * 1024
        const val MAX_CODE_ATTEMPTS = 5
    }
}
