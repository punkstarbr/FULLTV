/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import android.util.Log
import app.opentv.data.model.StremioStream
import app.opentv.data.parser.BoundedInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A thin, read-only client for the open Stremio add-on protocol — the same protocol Stremio and
 * many Kodi add-ons speak. OpenTV ships with no add-ons and hosts no content; this only talks to
 * manifest URLs the user has pasted in themselves.
 *
 * ## What it does
 * - [fetchManifest] fetches an add-on's `manifest.json` and returns its display name if it looks
 *   like a real add-on (has an id + name), so the settings screen can validate a pasted URL.
 * - [streams] asks an add-on for the streams it offers for a title and returns only the ones with a
 *   direct playable URL. Debrid-backed add-ons resolve a title to such a URL internally (using the
 *   debrid key baked into the user's personalised manifest URL), so OpenTV never handles a debrid
 *   key and never touches a torrent — it just plays the URL the add-on hands back.
 *
 * ## Blocking on purpose
 * Methods block on the calling thread; callers already run them inside `withContext(Dispatchers.IO)`,
 * matching [TmdbClient].
 */
class StremioClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    private val noRedirectHttp by lazy {
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    /**
     * Fetch and lightly validate an add-on manifest. Returns the add-on's display name, or null if
     * the URL doesn't resolve to something that looks like a Stremio manifest (id + name present).
     */
    fun fetchManifest(manifestUrl: String): String? {
        val url = safeHttpsUrl(manifestUrl) ?: return null
        val o = get(url, trustedOriginUrl = url.toString())?.jsonObject ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val hasId = o["id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        return if (hasId) name else null
    }

    /**
     * The streams [addonManifestUrl] offers for a title. [type] is "movie" or "series"; [id] is the
     * IMDb id — `tt1234567` for a film, `tt1234567:1:3` for series season 1 episode 3. Only entries
     * with a direct `url` are returned (see the class note on why torrents are skipped).
     */
    fun streams(addonManifestUrl: String, addonName: String, type: String, id: String): List<StremioStream> {
        if (type !in ALLOWED_TYPES || !id.matches(STREMIO_ID)) return emptyList()
        val addonOrigin = safeHttpsUrl(addonManifestUrl) ?: return emptyList()
        // A manifest URL is "<base>/manifest.json"; resource URLs hang off the same base.
        val base = addonManifestUrl.trim().removeSuffix("manifest.json").trimEnd('/')
        val url = safeHttpsUrl("$base/stream/$type/$id.json") ?: return emptyList()
        val streams = get(url, trustedOriginUrl = addonOrigin.toString())
            ?.jsonObject?.get("streams")?.jsonArray ?: return emptyList()
        return streams.take(MAX_STREAMS).mapNotNull { element ->
            val s = element.jsonObject
            val streamUrl = s["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null // infoHash-only (raw torrent) — skipped on purpose
            if (safeHttpsUrl(streamUrl) == null ||
                !NetworkDestinationPolicy.isAllowedHttps(streamUrl, trustedOriginUrl = addonOrigin.toString())
            ) return@mapNotNull null
            val label = (s["title"] ?: s["name"])?.jsonPrimitive?.contentOrNull
                ?.replace('\n', ' ')?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_LABEL_CHARS) ?: "Stream"
            StremioStream(url = streamUrl, title = label, addonName = addonName.take(MAX_LABEL_CHARS))
        }
    }

    private fun get(url: HttpUrl, trustedOriginUrl: String? = null): JsonElement? {
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val scope = NetworkDestinationPolicy.scopeFor(url.toString(), trustedOriginUrl) ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenTV")
            .tag(NetworkDestinationPolicy.RequestScope::class.java, scope)
            .build()
        return runCatching {
            var target = url
            repeat(MAX_REDIRECTS + 1) { hop ->
                var next: HttpUrl? = null
                val result = noRedirectHttp.newCall(request.newBuilder().url(target).build()).execute().use { response ->
                    if (response.isRedirect) {
                        if (hop >= MAX_REDIRECTS) return null
                        val location = response.header("Location") ?: return null
                        val redirect = target.resolve(location) ?: return null
                        if (!redirect.isHttps || redirect.host != url.host || redirect.port != url.port) return null
                        next = redirect
                        null
                    } else {
                        if (!response.isSuccessful) return null
                        val body = response.body ?: return null
                        if (body.contentLength() > MAX_JSON_BYTES) return null
                        val text = BoundedInputStream(body.byteStream(), MAX_JSON_BYTES, "Stremio response")
                            .use { String(it.readBytes(), Charsets.UTF_8) }
                        json.parseToJsonElement(text)
                    }
                }
                if (result != null) return result
                target = next ?: return null
            }
            null
        }.onFailure { Log.w(TAG, "Stremio request failed (${it.javaClass.simpleName})") }.getOrNull()
    }

    private fun safeHttpsUrl(raw: String): HttpUrl? {
        if (raw.length !in 9..MAX_URL_CHARS || raw.any { it.code <= 0x20 || it.code == 0x7f }) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        return url.takeIf {
            it.isHttps && it.host.isNotBlank() && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        }
    }

    private companion object {
        const val TAG = "StremioClient"
        const val MAX_JSON_BYTES = 8L * 1024 * 1024
        const val MAX_REDIRECTS = 3
        const val MAX_STREAMS = 100
        const val MAX_URL_CHARS = 8_192
        const val MAX_LABEL_CHARS = 256
        val ALLOWED_TYPES = setOf("movie", "series")
        val STREMIO_ID = Regex("[A-Za-z0-9:_-]{1,128}")
    }
}
