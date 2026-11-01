/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.backup

import app.opentv.sync.SyncBundle
import kotlinx.serialization.Serializable

/** Versioned, portable snapshot. The container encrypts this data before it is written to SAF. */
@Serializable
data class FullTvBackup(
    val version: Int = CURRENT_VERSION,
    val createdAtMillis: Long = 0L,
    val sources: List<BackupSource> = emptyList(),
    val epgFeeds: List<BackupEpgFeed> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
    val syncBundle: SyncBundle = SyncBundle(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Provider credentials are included only inside the password-encrypted backup container. */
@Serializable
data class BackupSource(
    val name: String,
    val kind: String,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val macAddress: String? = null,
    val epgUrl: String? = null,
    val userAgent: String,
    val liveFormat: String,
    val enabled: Boolean,
)

/** Non-provider feeds are identified by URL; local Room ids and sync stamps are deliberately absent. */
@Serializable
data class BackupEpgFeed(
    val name: String,
    val url: String,
    val enabled: Boolean,
    val builtIn: Boolean,
)

/** Portable app choices. SAF tree grants, device ids, local paths and the last channel are excluded. */
@Serializable
data class BackupSettings(
    val themeMode: String = "SYSTEM",
    val channelLayout: String = "GRID",
    val interfaceStyle: String = "CURRENT",
    val guideDensity: String = "NORMAL",
    val languageTag: String = "",
    val subtitlesEnabled: Boolean = true,
    val autoPortugueseSubtitles: Boolean = false,
    val guidePreviewVideo: Boolean = true,
    val guidePreviewSound: Boolean = false,
    val resumeLastChannel: Boolean = false,
    val catalogAutoRefresh: Boolean = true,
    val catalogRefreshIntervalHours: Int = 6,
    val catalogAskOnStart: Boolean = true,
    val liveEnabled: Boolean = true,
    val moviesEnabled: Boolean = true,
    val seriesEnabled: Boolean = true,
    val manualRecordMinutes: Int = 60,
    val recordPadStartMinutes: Int = 1,
    val recordPadEndMinutes: Int = 5,
    val recordScheduleLookaheadDays: Int = 2,
    val recordAutoSwitch: Boolean = true,
    val livePauseEnabled: Boolean = false,
    val playerResizeMode: Int = 0,
    val recordingContainer: String = "TS",
    /** USB is mapped to INTERNAL because its SAF permission cannot move between Android devices. */
    val recordingTarget: String = "INTERNAL",
    val smbHost: String = "",
    val smbShare: String = "",
    val smbFolder: String = "OpenTV",
    val smbUser: String = "",
    val smbPassword: String = "",
    val nasAutoSync: Boolean = false,
    val tmdbApiKey: String = "",
)

data class BackupRestoreResult(
    val sourcesImported: Int,
    val feedsImported: Int,
    val syncFailures: Int,
    val mergedItems: Int,
)
