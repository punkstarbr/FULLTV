/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import android.util.Log
import app.opentv.core.AppSettings
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.shownName
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbConfig
import app.opentv.security.LanConnectionLimiter
import app.opentv.security.LanTlsServer
import app.opentv.security.ParentalAuthorization
import java.io.OutputStream
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Serves a "manage your channels from your phone or laptop" web app off this television.
 *
 * ## Same ethos as [PairingServer]
 *
 * There is no cloud. The TV runs a tiny HTTP server on the local network for as long as the manage
 * screen is open; a phone or laptop on the same wifi opens a real page — with a keyboard and mouse
 * instead of a D-pad — and browses, hides, favourites, renames and reorders channels. Every change
 * writes straight to this device's database. It works with the internet unplugged.
 *
 * ## What stops the neighbours using it
 *
 * - Binds to the LAN on a **random high port**, and only while the manage screen is open.
 * - The URL carries a **random token**; every route checks it. Knowing the IP and port is not
 *   enough. The token is only ever shown on the TV (QR + link), so only someone who can see the
 *   telly can connect — which is the whole security model. It is kept short enough to type, because
 *   the TV shows the full URL (with the token) as a fallback for when the QR won't scan; a LAN-only,
 *   private-IP-only, idle-closing server on a random port doesn't need more. Unlike pairing there is
 *   no six-digit code, because this handles no credentials and the point is to be frictionless.
 * - Requests from non-private addresses are refused outright.
 * - Request bodies are capped ([MAX_BODY_BYTES]).
 * - Unlike pairing (which closes after the first submit) this keeps serving while the screen is
 *   open, and only closes itself after [IDLE_TIMEOUT_MINUTES] with no requests, or on [stop].
 */
class ManagerServer(
    private val scope: CoroutineScope,
    private val catalog: CatalogRepository,
    private val settings: AppSettings,
    private val sourceRepository: SourceRepository,
) {

    data class Session(
        /** What the QR code encodes, e.g. `https://192.168.0.14:41234/?t=…` */
        val url: String,
        val host: String,
        val port: Int,
        /** SHA-256 of the DER certificate, for out-of-band browser verification. */
        val certificateSha256: String,
    ) {
        /** The address to type if the QR will not scan. */
        val shortUrl: String get() = "$host:$port"
    }

    sealed interface State {
        data object Idle : State
        data class Listening(val session: Session) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var serverSocket: LanTlsServer.Listener? = null
    private var token: String = ""
    @Volatile private var running = false
    @Volatile private var starting = false
    @Volatile private var generation = 0
    @Volatile private var lastActivityMillis = 0L
    private var startJob: Job? = null
    private val admission = LanConnectionLimiter()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
                        "This device doesn't appear to be on a network, so a phone or laptop can't reach it.",
                    )
                    return@launch
                }

                val random = SecureRandom()
                val nextToken = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                val opened = LanTlsServer.open(host)
                listener = opened
                val installed = synchronized(this@ManagerServer) {
                    if (runGeneration != generation) {
                        false
                    } else {
                        token = nextToken
                        serverSocket = opened
                        running = true
                        lastActivityMillis = System.currentTimeMillis()
                        admission.reset()
                        true
                    }
                }
                if (!installed) return@launch
                val activeListener = opened
                _state.value = State.Listening(
                    Session(
                        url = "https://${activeListener.host}:${activeListener.port}/?t=$token",
                        host = activeListener.host,
                        port = activeListener.port,
                        certificateSha256 = LanTlsServer.formatCertificateFingerprint(activeListener.certificateSha256),
                    ),
                )

                // Complete TLS before the HTTP handler reads the manager token or any submitted data.
                thread(name = "opentv-manager-tls", isDaemon = true) {
                    while (running && runGeneration == generation) {
                        val client = try {
                            activeListener.acceptClient(SOCKET_TIMEOUT_MILLIS)
                        } catch (error: Exception) {
                            if (!running || runGeneration != generation) break
                            Log.w(TAG, "Rejected manager TLS connection (${error.javaClass.simpleName})")
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
                        thread(name = "opentv-manager-request", isDaemon = true) {
                            try {
                                activeListener.completeHandshake(client)
                                if (running && runGeneration == generation) {
                                    LanTlsServer.closeAfter(client, RESPONSE_DEADLINE_MILLIS).use {
                                        handle(client, "${activeListener.host}:${activeListener.port}")
                                    }
                                }
                            } catch (error: Exception) {
                                Log.w(TAG, "Manager request failed (${error.javaClass.simpleName})")
                            } finally {
                                runCatching { client.close() }
                                permit.close()
                            }
                        }
                    }
                }
                listener = null // ownership transferred to stop()/the accept loop

                // Idle watchdog: keep serving while the screen is open, but close an abandoned listener.
                scope.launch(Dispatchers.Default) {
                    while (running && runGeneration == generation) {
                        delay(TimeUnit.MINUTES.toMillis(1))
                        val idleFor = System.currentTimeMillis() - lastActivityMillis
                        if (running && runGeneration == generation && idleFor > TimeUnit.MINUTES.toMillis(IDLE_TIMEOUT_MINUTES)) {
                            stop()
                            _state.value = State.Failed(
                                "The channel manager closed after a while unused. Reopen it when you need it.",
                            )
                            break
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not start TLS manager (${error.javaClass.simpleName})")
                if (runGeneration == generation) {
                    _state.value = State.Failed("Could not start the secure channel manager on this device.")
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
        if (_state.value is State.Listening) _state.value = State.Idle
    }

    // ---- Request handling ----------------------------------------------------------------------

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
        if (request.method == "POST" && !PairingHttp.isExpectedOrigin(request.headers["origin"], expectedAuthority)) {
            output.write(PairingHttp.httpResponse("403 Forbidden", "text/plain", "Cross-origin mutation refused."))
            return
        }

        // Every route checks the token. Without it, none of this exists.
        val supplied = request.query["t"] ?: ""
        if (token.isEmpty() || !PairingHttp.secretsMatch(supplied, token)) {
            output.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
            return
        }

        // A valid request counts as activity; the idle watchdog measures from here.
        lastActivityMillis = System.currentTimeMillis()
        if (request.method == "POST" && request.headers["content-type"]
                ?.substringBefore(';')?.trim()?.equals("application/json", ignoreCase = true) != true
        ) {
            output.write(PairingHttp.httpResponse("415 Unsupported Media Type", "text/plain", "JSON required."))
            return
        }
        val body = request.body

        when {
            request.method == "GET" && request.path == "/" ->
                output.write(PairingHttp.httpResponse("200 OK", "text/html", ManagerPage.page(token)))

            request.method == "GET" && request.path == "/meta" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", metaJson()))

            request.method == "GET" && request.path == "/channels" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", channelsJson(request.query)))

            request.method == "POST" && request.path == "/channel" -> handleChannelPatch(output, body)

            request.method == "POST" && request.path == "/reorder" -> handleReorder(output, body)

            request.method == "GET" && request.path == "/recording" ->
                output.write(PairingHttp.httpResponse("200 OK", "application/json", recordingJson()))

            request.method == "POST" && request.path == "/recording" -> handleRecording(output, body)

            request.method == "POST" && request.path == "/recording/test" -> handleRecordingTest(output, body)

            request.method == "POST" && request.path == "/source" -> handleAddSource(output, body)

            request.method == "POST" && request.path == "/source/test" -> handleTestSource(output, body)

            else -> output.write(PairingHttp.httpResponse("404 Not Found", "text/plain", "Not found."))
        }
    }

    // ---- Routes --------------------------------------------------------------------------------

    /** `{ sources:[{id,name}], categories:[{id,label,sourceId,count}] }` */
    private fun metaJson(): String = runBlocking {
        val sources = catalog.enabledSources()
        val sourceIds = sources.map { it.id }.toSet()
        val counts = catalog.categoryChannelCounts()
            .associateBy({ it.sourceId to it.categoryId }, { it.count })
        // Only categories that belong to an enabled source AND actually hold channels — an empty
        // category is noise on a phone, and a category count includes hidden rows so it stays > 0
        // for a category whose channels are all hidden (which is exactly what you'd browse to fix).
        val categories = catalog.liveCategories()
            .filter { it.sourceId in sourceIds }
            .mapNotNull { cat ->
                val count = counts[cat.sourceId to cat.id] ?: 0
                if (count <= 0) null
                else CategoryDto(id = cat.id, label = cat.name, sourceId = cat.sourceId, count = count)
            }
        json.encodeToString(
            MetaDto.serializer(),
            MetaDto(sources = sources.map { SourceDto(it.id, it.name) }, categories = categories),
        )
    }

    /** `GET /channels?cat=<id>[&source=<id>]` → the category's channels, hidden included. */
    private fun channelsJson(query: Map<String, String>): String = runBlocking {
        val serializer = ListSerializer(ChannelDto.serializer())
        val cat = query["cat"]
        if (cat.isNullOrBlank()) return@runBlocking json.encodeToString(serializer, emptyList())
        val source = query["source"]?.toLongOrNull()
        val rows = catalog.channelsInCategoryForManager(source, cat).map { it.toDto() }
        json.encodeToString(serializer, rows)
    }

    /** `POST /channel {id, hidden?|favourite?|name?}` → apply the given field(s). */
    private fun handleChannelPatch(output: OutputStream, body: String) {
        val patch = runCatching { json.decodeFromString(ChannelPatch.serializer(), body) }.getOrNull()
        if (patch == null || patch.id <= 0L) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        if (patch.hidden == false &&
            !ParentalAuthorization.mayUnhide(settings.pinIsSet.value, settings.hiddenUnlocked.value)
        ) {
            output.write(
                PairingHttp.httpResponse(
                    "403 Forbidden",
                    "text/plain",
                    "Parental PIN required on the TV before hidden channels can be shown remotely.",
                ),
            )
            return
        }
        // Unknown ids simply update zero rows — harmless, so no existence check.
        runBlocking {
            patch.hidden?.let { catalog.setChannelHidden(patch.id, it) }
            patch.favourite?.let { catalog.setChannelFavourite(patch.id, it) }
            // A blank name clears the rename back to the provider's own (see setChannelCustomName).
            patch.name?.let { catalog.setChannelCustomName(patch.id, it) }
        }
        writeOk(output)
    }

    /** `POST /reorder {ids:[orderedChannelIds]}` → assign sortIndex by position. */
    private fun handleReorder(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(ReorderReq.serializer(), body) }.getOrNull()
        if (req == null) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        runBlocking {
            req.ids.forEachIndexed { index, id -> if (id > 0L) catalog.setChannelSortIndex(id, index) }
        }
        writeOk(output)
    }

    private fun writeOk(output: OutputStream) {
        output.write(
            PairingHttp.httpResponse("200 OK", "application/json", json.encodeToString(OkDto.serializer(), OkDto())),
        )
    }

    /** `GET /recording` → the current recording target and NAS settings. The password is never sent
     *  back to the browser — only whether one is set. */
    private fun recordingJson(): String = json.encodeToString(
        RecordingDto.serializer(),
        RecordingDto(
            target = settings.recordingTarget.value.name,
            host = settings.smbHost.value,
            share = settings.smbShare.value,
            folder = settings.smbFolder.value,
            user = settings.smbUser.value,
            hasPassword = settings.smbPassword.value.isNotEmpty(),
        ),
    )

    /** `POST /recording {host,share,folder,user,password?}` → save the NAS details and switch
     *  recordings to it. A blank/absent password keeps the one already stored, so the fields can be
     *  filled without re-typing an existing password. */
    private fun handleRecording(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(RecordingReq.serializer(), body) }.getOrNull()
        if (req == null || req.host.isBlank() || req.share.isBlank()) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "A server address and share are required."))
            return
        }
        val host = req.host.trim()
        val share = req.share.trim()
        val user = req.user.trim()
        val sameAuthenticationTarget = host.equals(settings.smbHost.value, ignoreCase = true) &&
            share.equals(settings.smbShare.value, ignoreCase = true) && user == settings.smbUser.value
        val submittedPassword = req.password?.takeIf { it.isNotEmpty() }
        if (!sameAuthenticationTarget && submittedPassword == null && settings.smbPassword.value.isNotEmpty()) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Enter the password when changing the NAS target."))
            return
        }
        val password = submittedPassword ?: if (sameAuthenticationTarget) settings.smbPassword.value else ""
        settings.setSmbConfig(host, share, req.folder.trim(), user, password)
        settings.setRecordingTarget(AppSettings.RecordingTarget.SMB)
        writeOk(output)
    }

    /** `POST /recording/test {host,share,folder,user,password?}` → actually open the SMB share and
     *  report whether it worked. Blank fields fall back to what's stored, so the browser can test
     *  the saved settings without re-sending the password. Runs on this handler thread (already off
     *  the main thread), which is where a blocking network probe belongs. */
    private fun handleRecordingTest(output: OutputStream, body: String) {
        val req = runCatching { json.decodeFromString(RecordingReq.serializer(), body) }.getOrNull()
        if (req == null) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Bad request."))
            return
        }
        val host = req.host.trim().ifBlank { settings.smbHost.value }
        val share = req.share.trim().ifBlank { settings.smbShare.value }
        val user = req.user.trim().ifBlank { settings.smbUser.value }
        if (host.isBlank() || share.isBlank()) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "A server address and share are required."))
            return
        }
        val sameAuthenticationTarget = host.equals(settings.smbHost.value, ignoreCase = true) &&
            share.equals(settings.smbShare.value, ignoreCase = true) && user == settings.smbUser.value
        val submittedPassword = req.password?.takeIf { it.isNotEmpty() }
        if (!sameAuthenticationTarget && submittedPassword == null && settings.smbPassword.value.isNotEmpty()) {
            output.write(PairingHttp.httpResponse("400 Bad Request", "text/plain", "Enter the password before testing a different NAS target."))
            return
        }
        val cfg = SmbConfig(
            host = host,
            share = share,
            folder = req.folder.trim().ifBlank { settings.smbFolder.value },
            username = user,
            password = submittedPassword ?: if (sameAuthenticationTarget) settings.smbPassword.value else "",
        )
        val result = runCatching { SmbClient.test(cfg) }
        val dto = if (result.isSuccess) {
            TestDto(ok = true, error = null)
        } else {
            val e = result.exceptionOrNull()
            TestDto(ok = false, error = e?.message ?: e?.javaClass?.simpleName ?: "Connection failed")
        }
        output.write(PairingHttp.httpResponse("200 OK", "application/json", json.encodeToString(TestDto.serializer(), dto)))
    }

    // ---- Add a provider ------------------------------------------------------------------------

    /**
     * `POST /source {kind,name,url,username?,password?,mac?}` → save the provider and pull its live
     * channels, so the catalogue fills without anyone typing a server address on the TV remote.
     * Mirrors the on-device Add-source screen: save, then load live channels first.
     */
    private fun handleAddSource(output: OutputStream, body: String) {
        val draft = runCatching { json.decodeFromString(SourceReq.serializer(), body) }.getOrNull()?.toDraftOrNull()
        if (draft == null) {
            writeJson(output, AddResultDto.serializer(), AddResultDto(ok = false, error = "Fill in the required fields for this provider type."))
            return
        }
        val result = runCatching {
            runBlocking {
                val id = sourceRepository.save(draft)
                val saved = sourceRepository.byId(id)
                    ?: return@runBlocking AddResultDto(ok = false, error = "Could not save the source.")
                when (val r = catalog.syncLive(saved, System.currentTimeMillis())) {
                    is CatalogRepository.SyncResult.Success -> AddResultDto(ok = true, channels = r.channelCount)
                    is CatalogRepository.SyncResult.Failed -> AddResultDto(ok = false, error = r.reason)
                }
            }
        }.getOrElse { error ->
            AddResultDto(ok = false, error = error.message ?: "Could not save the source.")
        }
        writeJson(output, AddResultDto.serializer(), result)
    }

    /** `POST /source/test {…}` → validate the provider details without saving (handshake / auth). */
    private fun handleTestSource(output: OutputStream, body: String) {
        val draft = runCatching { json.decodeFromString(SourceReq.serializer(), body) }.getOrNull()?.toDraftOrNull()
        if (draft == null) {
            writeJson(output, TestResultDto.serializer(), TestResultDto(ok = false, error = "Fill in the required fields for this provider type."))
            return
        }
        val result = runBlocking { sourceRepository.test(draft) }
        val dto = if (result.isSuccess) {
            TestResultDto(ok = true, message = result.getOrNull())
        } else {
            TestResultDto(ok = false, error = result.exceptionOrNull()?.message ?: "Test failed.")
        }
        writeJson(output, TestResultDto.serializer(), dto)
    }

    /** Turn a web request into a [Source] draft, or null if a field required for its kind is missing. */
    private fun SourceReq.toDraftOrNull(): Source? {
        val k = runCatching { SourceKind.valueOf(kind.trim().uppercase()) }.getOrNull() ?: return null
        if (url.isBlank()) return null
        when (k) {
            SourceKind.XTREAM -> if (username.isNullOrBlank() || password.isNullOrBlank()) return null
            SourceKind.STALKER -> if (mac.isNullOrBlank()) return null
            SourceKind.YOUTUBE_LIVE_SEARCH -> Unit
            SourceKind.M3U -> Unit
        }
        return Source(
            name = name.trim().ifBlank {
                when (k) {
                    SourceKind.M3U -> "Playlist"
                    SourceKind.YOUTUBE_LIVE_SEARCH -> "YouTube Live Search"
                    else -> "Provider"
                }
            },
            kind = k,
            url = url.trim(),
            username = username?.takeIf { it.isNotBlank() },
            password = password?.takeIf { it.isNotBlank() },
            macAddress = mac?.takeIf { it.isNotBlank() },
            userAgent = Source.DEFAULT_USER_AGENT,
        )
    }

    private fun <T> writeJson(output: OutputStream, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        output.write(PairingHttp.httpResponse("200 OK", "application/json", json.encodeToString(serializer, value)))
    }

    private fun Channel.toDto() = ChannelDto(
        id = id,
        name = shownName,
        original = displayName,
        hidden = hidden,
        favourite = favourite,
        sortIndex = sortIndex,
        logo = logoUrl,
        number = number,
    )

    // ---- Finding our own address ---------------------------------------------------------------

    /**
     * The device's address on the LAN.
     *
     * Deliberately the same enumeration as [PairingServer.lanAddress] and [app.opentv.sync.SyncServer]
     * (duplicated, not shared, to keep the servers independent): asks the interfaces rather than
     * ConnectivityManager, because TV boxes are frequently on ethernet and the wifi-shaped API
     * returns nothing useful when the box is wired.
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

        // Prefer a real private range over a 169.254 self-assigned address, which means DHCP
        // failed and nothing will be able to reach us anyway.
        return candidates
            .sortedBy { it.hostAddress?.startsWith("169.254") == true }
            .firstOrNull()
            ?.hostAddress
    }

    private companion object {
        const val TAG = "ManagerServer"
        const val SOCKET_TIMEOUT_MILLIS = 5_000
        const val RESPONSE_DEADLINE_MILLIS = 10_000L
        const val IDLE_TIMEOUT_MINUTES = 30L

        // Generous next to pairing's 16 KB: a /reorder body carries every channel id in a
        // category, which can be a few thousand on a big provider.
        const val MAX_BODY_BYTES = 256 * 1024
    }
}

// ---- JSON DTOs ---------------------------------------------------------------------------------
// kotlinx.serialization handles the escaping that matters here: channel names carry quotes, `|`
// and unicode, and hand-rolled string concatenation is exactly where that goes wrong.

@Serializable private data class MetaDto(val sources: List<SourceDto>, val categories: List<CategoryDto>)

@Serializable private data class SourceDto(val id: Long, val name: String)

@Serializable private data class CategoryDto(val id: String, val label: String, val sourceId: Long, val count: Int)

@Serializable private data class ChannelDto(
    val id: Long,
    val name: String,
    val original: String,
    val hidden: Boolean,
    val favourite: Boolean,
    val sortIndex: Int,
    val logo: String?,
    val number: Int?,
)

@Serializable private data class OkDto(val ok: Boolean = true)

@Serializable private data class ChannelPatch(
    val id: Long,
    val hidden: Boolean? = null,
    val favourite: Boolean? = null,
    val name: String? = null,
)

@Serializable private data class ReorderReq(val ids: List<Long>)

@Serializable private data class RecordingDto(
    val target: String,
    val host: String,
    val share: String,
    val folder: String,
    val user: String,
    val hasPassword: Boolean,
)

@Serializable private data class RecordingReq(
    val host: String = "",
    val share: String = "",
    val folder: String = "",
    val user: String = "",
    val password: String? = null,
)

@Serializable private data class TestDto(val ok: Boolean, val error: String?)

@Serializable private data class SourceReq(
    val kind: String = "xtream",
    val name: String = "",
    val url: String = "",
    val username: String? = null,
    val password: String? = null,
    val mac: String? = null,
)

@Serializable private data class AddResultDto(val ok: Boolean, val channels: Int = 0, val error: String? = null)

@Serializable private data class TestResultDto(val ok: Boolean, val message: String? = null, val error: String? = null)
