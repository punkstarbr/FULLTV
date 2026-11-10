/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.remote.NetworkDestinationPolicy

/** Bounded, independent origin budgets prevent a large stream catalogue from crowding out its EPG. */
internal object M3uOriginPolicy {
    const val MAX_SAFE_MEDIA_ORIGINS = 128
    const val MAX_SAFE_GUIDE_ORIGINS = 128
    private const val MAX_GUIDE_ORIGIN_CANDIDATES = 512

    fun selectAllowedOrigins(
        trustedOrigin: String?,
        mediaUrls: Iterable<String>,
        guideUrls: Iterable<String>,
        isAllowedHttpsOrigin: (String) -> Boolean,
    ): Set<String> {
        val mediaOrigins = NetworkDestinationPolicyOrigins.distinct(mediaUrls)
            .asSequence()
            .filterNot { it == trustedOrigin }
            .take(MAX_SAFE_MEDIA_ORIGINS)
            .filter(isAllowedHttpsOrigin)
            .toList()
        val guideOrigins = NetworkDestinationPolicyOrigins.distinct(guideUrls)
            .asSequence()
            .filterNot { it == trustedOrigin }
            .take(MAX_GUIDE_ORIGIN_CANDIDATES)
            // Invalid/private origins do not consume the separate, bounded guide quota.
            .filter(isAllowedHttpsOrigin)
            .take(MAX_SAFE_GUIDE_ORIGINS)
            .toList()

        return buildSet {
            trustedOrigin?.let(::add)
            addAll(mediaOrigins)
            addAll(guideOrigins)
        }
    }

    private object NetworkDestinationPolicyOrigins {
        fun distinct(urls: Iterable<String>): List<String> =
            urls.mapNotNull(NetworkDestinationPolicy::originOf).distinct()
    }
}
