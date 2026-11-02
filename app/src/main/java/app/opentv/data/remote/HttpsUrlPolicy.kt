/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

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
    }
}
