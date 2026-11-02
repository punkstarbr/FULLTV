/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A user-facing validation failure: unencrypted HTTP is blocked by the Android network policy. */
class HttpsRequiredException : IllegalArgumentException(
    "HTTP is blocked for privacy. Use an HTTPS URL.",
)

/** Shared URL policy for provider and EPG requests. Do not weaken the app-wide cleartext policy. */
object HttpsUrlPolicy {
    const val REQUIRED_RESULT = "HTTPS_REQUIRED"

    fun withDefaultHttps(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.contains("://")) return value
        return "https://$value"
    }

    fun isHttps(raw: String?): Boolean = raw?.trim()?.startsWith("https://", ignoreCase = true) == true

    fun isCleartext(raw: String?): Boolean = raw?.trim()?.startsWith("http://", ignoreCase = true) == true

    /** Bare hosts are upgraded at save time; an explicit non-HTTPS scheme is never upgraded. */
    fun requiresSecureScheme(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        return value.contains("://") && !isHttps(value)
    }

    fun requireHttps(raw: String?) {
        if (raw.isNullOrBlank()) return
        if (!isHttps(raw)) throw HttpsRequiredException()
        val value = raw.trim()
        require(value.length <= MAX_URL_CHARS) { "URL is too long." }
        require(value.none { it.code <= 0x20 || it.code == 0x7f }) { "URL contains invalid control characters." }
        val parsed = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid HTTPS URL.")
        require(parsed.isHttps && parsed.host.isNotBlank()) { "Invalid HTTPS URL host." }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "Credentials must not be embedded in the URL authority."
        }
        require(parsed.fragment == null) { "URL fragments are not sent to the server." }
    }

    private const val MAX_URL_CHARS = 16_384
}
