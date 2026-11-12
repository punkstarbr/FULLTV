/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind

/** Startup-only policy: a blank guide is refreshed immediately; otherwise respect its age limit. */
internal object EpgStartupRefreshPolicy {
    fun shouldSyncFeed(
        lastSyncMillis: Long,
        nowUtcMillis: Long,
        refreshIntervalMillis: Long,
        guideSnapshotEmpty: Boolean,
    ): Boolean {
        if (guideSnapshotEmpty || lastSyncMillis <= 0L) return true
        if (nowUtcMillis <= lastSyncMillis) return false
        return nowUtcMillis - lastSyncMillis >= refreshIntervalMillis.coerceAtLeast(0L)
    }

    fun hasUsableGuideSource(feed: EpgFeed, provider: Source?): Boolean {
        if (!feed.url.isNullOrBlank()) return true
        if (provider == null) return false
        return provider.kind != SourceKind.M3U || !provider.epgUrl.isNullOrBlank()
    }
}
