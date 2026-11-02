/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import kotlinx.serialization.Serializable

/**
 * Read-only decoder for the v0.11.22 NAS sync shape. It contains raw provider URLs and must never
 * be written back out. [SyncEngine.migrateLegacyV3] converts it to HMAC identifiers before use.
 */
@Serializable
internal data class LegacySyncBundleV3(
    val version: Int = 0,
    val generatedAtMillis: Long = 0,
    val profiles: List<String> = emptyList(),
    val positions: List<LegacySyncPositionV3> = emptyList(),
    val favouriteChannels: List<String> = emptyList(),
    val hiddenChannels: List<String> = emptyList(),
    val favouriteMovies: List<String> = emptyList(),
    val favouriteSeries: List<String> = emptyList(),
    val hiddenCategories: List<String> = emptyList(),
    val recordings: List<LegacySyncRecordingV3> = emptyList(),
)

@Serializable
internal data class LegacySyncPositionV3(
    val profile: String = "",
    val streamUrl: String = "",
    val kind: String = "",
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val updatedAtMillis: Long = 0,
)

@Serializable
internal data class LegacySyncRecordingV3(
    val channelName: String = "",
    val logoUrl: String? = null,
    val title: String = "",
    val description: String? = null,
    val filePath: String = "",
    val streamUrl: String = "",
    val userAgent: String = "",
    val scheduledStartMillis: Long = 0,
    val scheduledEndMillis: Long = 0,
    val startedAtMillis: Long = 0,
    val endedAtMillis: Long = 0,
    val sizeBytes: Long = 0,
)
