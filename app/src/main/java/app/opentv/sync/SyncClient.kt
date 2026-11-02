/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.pairing.PairingHttp
import app.opentv.security.LanTlsServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/**
 * Pulls a watch-history bundle from another FullTV currently sharing on the LAN.
 *
 * HTTPS is mandatory. The receiver enters the 96-bit SPKI verification code shown on the sender;
 * the dedicated trust manager checks that pin before OkHttp sends the six-digit access code. The
 * default hostname verifier remains enabled, redirects/retries are disabled, and the response is
 * size-limited before JSON parsing.
 */
class SyncClient {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun pull(address: String, code: String, syncPin: String): Result<SyncBundle> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = parseAddress(address)
                val cleanCode = code.trim()
                require(cleanCode.matches(Regex("\\d{6}"))) {
                    "Informe o código de 6 dígitos exibido no outro FullTV."
                }
                val pin = LanTlsServer.normalizeSyncPin(syncPin)
                val trustManager = LanTlsServer.pinnedTrustManager(pin)
                val sslContext = SSLContext.getInstance("TLSv1.2").apply {
                    init(null, arrayOf(trustManager), null)
                }
                val client = OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustManager)
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .retryOnConnectionFailure(false)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .callTimeout(15, TimeUnit.SECONDS)
                    .build()

                val url = endpoint.newBuilder()
                    .addPathSegment("export")
                    .addQueryParameter("c", cleanCode)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("X-FullTV-Sync", "1")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        error("O outro FullTV tentou redirecionar a conexão; o redirecionamento foi bloqueado.")
                    }
                    if (!response.isSuccessful) {
                        error(
                            when (response.code) {
                                401 -> "O código não corresponde ao mostrado no outro FullTV."
                                else -> "O outro dispositivo recusou a conexão (HTTP ${response.code})."
                            },
                        )
                    }
                    val mediaType = response.header("Content-Type")
                        ?.substringBefore(';')
                        ?.trim()
                        ?.lowercase()
                    if (mediaType != "application/json") {
                        error("O outro FullTV retornou um formato inesperado.")
                    }
                    val body = response.body ?: error("A resposta do outro FullTV estava vazia.")
                    if (body.contentLength() > MAX_RESPONSE_BYTES) {
                        error("O bundle do outro FullTV ultrapassa o limite de segurança.")
                    }
                    val bytes = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_RESPONSE_BYTES) {
                                throw IOException("O bundle do outro FullTV ultrapassa o limite de segurança.")
                            }
                            bytes.write(buffer, 0, count)
                        }
                    }
                    val payload = bytes.toByteArray().toString(Charsets.UTF_8)
                    json.decodeFromString(SyncBundle.serializer(), payload)
                }
            }
        }

    companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        /** Accept only a bare private IPv4 address with an explicit high HTTPS port. */
        internal fun parseAddress(address: String): HttpUrl {
            val raw = address.trim()
            require(raw.isNotEmpty()) { "Informe o endereço HTTPS exibido no outro FullTV." }
            require(!raw.startsWith("http://", ignoreCase = true)) {
                "Sincronização sem criptografia foi desativada. Use o endereço https:// exibido na outra TV."
            }
            val normalized = if (raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
            val parsed = normalized.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("O endereço HTTPS do outro FullTV é inválido.")
            require(parsed.scheme == "https") { "Sincronização exige HTTPS." }
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
                "O endereço não pode conter usuário ou senha."
            }
            require(parsed.encodedPath == "/" && parsed.query == null && parsed.fragment == null) {
                "Informe somente o endereço e a porta, sem caminho ou parâmetros."
            }
            require(parsed.port >= 1024) { "Informe a porta HTTPS completa exibida no outro FullTV." }
            val octets = parsed.host.split('.')
            require(octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
                "Use o IPv4 privado mostrado no outro FullTV."
            }
            val resolved = InetAddress.getByAddress(ByteArray(4) { index -> octets[index].toInt().toByte() })
            require(PairingHttp.isPrivateAddress(resolved.hostAddress)) {
                "A sincronização aceita somente um IPv4 de rede privada."
            }
            return parsed
        }
    }
}
