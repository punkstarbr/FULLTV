/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

/** Freshness policy for the expensive movies/series catalogue. */
internal object VodRefreshPolicy {
    const val TTL_MILLIS: Long = 12L * 60 * 60 * 1000

    fun shouldRefresh(lastSyncedAtMillis: Long, nowMillis: Long, force: Boolean = false): Boolean =
        force || lastSyncedAtMillis <= 0L || nowMillis < lastSyncedAtMillis ||
            nowMillis - lastSyncedAtMillis >= TTL_MILLIS
}
