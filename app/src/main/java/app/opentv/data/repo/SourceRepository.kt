/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.SourceDao
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.parser.YouTubeLiveSearchUrl
import app.opentv.data.remote.HttpsUrlPolicy
import app.opentv.data.remote.NetworkDestinationPolicy
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SourceRepository(
    private val dao: SourceDao,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
) {
    fun observeAll(): Flow<List<Source>> = dao.observeAll()

    suspend fun enabled(): List<Source> = dao.enabled().also { sources ->
        sources.forEach(::registerConfiguredOrigins)
    }

    suspend fun byId(id: Long): Source? = dao.byId(id)?.also(::registerConfiguredOrigins)

    suspend fun save(source: Source): Long = withContext(Dispatchers.IO) {
        val normalised = normaliseAndValidate(source)
        registerConfiguredOrigins(normalised)
        if (source.id == 0L) dao.insert(normalised)
        else {
            dao.update(normalised)
            source.id
        }
    }

    /** Checks the details work before the user is committed to them. */
    suspend fun test(source: Source): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val secure = normaliseAndValidate(source)
            registerConfiguredOrigins(secure)
            when (secure.kind) {
                SourceKind.XTREAM -> {
                    val info = api.authenticate(secure)
                    buildString {
                        append("Connected")
                        info.username?.let { append(" as $it") }
                        info.maxConnections?.let { append(" · $it connection(s)") }
                        info.expiryMillis?.let {
                            append(" · expires ${java.text.DateFormat.getDateInstance().format(java.util.Date(it))}")
                        }
                    }
                }
                SourceKind.M3U -> "HTTPS playlist URL format is valid. This check does not contact the server; the first sync will verify it."
                SourceKind.YOUTUBE_LIVE_SEARCH -> {
                    if (YouTubeLiveSearchUrl.normalize(source.url) == null) {
                        error("Enter a YouTube /results URL with a search_query parameter.")
                    }
                    "YouTube search URL format looks valid. Live results are checked during sync."
                }
                SourceKind.STALKER -> {
                    stalkerApi.handshakeTest(secure)
                    "Portal accepted the MAC address. Loading channels…"
                }
            }
        }
    }

    private fun registerConfiguredOrigins(source: Source) {
        NetworkDestinationPolicy.registerTrustedOrigin(source.url)
        source.epgUrl?.let(NetworkDestinationPolicy::registerTrustedOrigin)
    }

    companion object {
        /**
         * Users paste all sorts of things. Accept them all rather than making someone guess
         * the format: a trailing slash, a missing scheme, or a full `get.php` URL copied out
         * of a provider's welcome email.
         *
         * For an Xtream source we want the bare host, so API paths are stripped. For an M3U
         * source the URL *is* the playlist — stripping the path there would destroy it, which
         * is why [kind] is not optional.
         *
         * Pure and static so it can be tested without standing up the repository.
         */
        fun normaliseUrl(raw: String, kind: SourceKind): String {
            var url = raw.trim()
            if (url.isEmpty()) return url
            url = HttpsUrlPolicy.withDefaultHttps(url)
            if (kind == SourceKind.M3U) return url
            if (kind == SourceKind.YOUTUBE_LIVE_SEARCH) {
                return YouTubeLiveSearchUrl.normalize(url) ?: url
            }
            // A Stalker portal URL is the portal path itself (e.g. .../stalker_portal or .../c) —
            // stripping Xtream API paths would be wrong; just tidy the trailing slash.
            if (kind == SourceKind.STALKER) return url.trimEnd('/')

            url = url.substringBefore("/player_api.php")
                .substringBefore("/panel_api.php")
                .substringBefore("/get.php")
                .substringBefore("/xmltv.php")
            return url.trimEnd('/')
        }

        private fun normaliseAndValidate(source: Source): Source {
            require(source.url.length <= MAX_URL_CHARS) { "Source URL exceeds the safety limit." }
            val epgUrl = source.epgUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(HttpsUrlPolicy::withDefaultHttps)
            val normalized = source.copy(
                url = normaliseUrl(source.url, source.kind),
                epgUrl = epgUrl,
            )
            require(normalized.url.isNotBlank()) { "Enter a source URL." }
            require(normalized.name.isNotBlank() && normalized.name.length <= MAX_NAME_CHARS &&
                normalized.name.none { it.code < 0x20 || it.code == 0x7f }
            ) { "Source name is invalid or too long." }
            require(normalized.username.orEmpty().length <= MAX_CREDENTIAL_CHARS &&
                normalized.password.orEmpty().length <= MAX_CREDENTIAL_CHARS &&
                normalized.macAddress.orEmpty().length <= MAX_MAC_CHARS
            ) { "Source credentials exceed the safety limit." }
            require(normalized.userAgent.length <= MAX_USER_AGENT_CHARS &&
                normalized.userAgent.none { it == '\r' || it == '\n' || it.code == 0x7f }
            ) { "User-Agent is invalid or too long." }
            HttpsUrlPolicy.requireHttps(normalized.url)
            HttpsUrlPolicy.requireHttps(normalized.epgUrl)
            return normalized
        }

        private const val MAX_NAME_CHARS = 120
        private const val MAX_CREDENTIAL_CHARS = 4_096
        private const val MAX_MAC_CHARS = 64
        private const val MAX_USER_AGENT_CHARS = 1_024
        private const val MAX_URL_CHARS = 16_384
    }
}
