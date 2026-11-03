/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.core.ServiceLocator
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Free, server-less "cloud" sync through the user's own NAS.
 *
 * There is no server of ours and no account: every device drops its own [SyncBundle] as a small
 * JSON file into one shared folder on the SMB share the user already set up for recordings, and
 * reads back every other device's file. The folder is a plain rendezvous — the merge logic lives
 * in [SyncEngine], exactly the one used by LAN sync, so favourites, watch history and NAS
 * recordings converge the same way whether they arrive over wifi or via the NAS.
 *
 * Each device writes `<share>/<folder>/OpenTV/sync/<deviceId>.json`. Reading is additive and
 * newest-wins (see [SyncEngine]); nothing on the NAS is ever deleted, so a device that has been
 * offline for months still contributes when it next syncs.
 */
class NasSync(private val graph: ServiceLocator.Graph) {

    private val engine = SyncEngine(graph)

    sealed interface Result {
        /** [merged] items pulled in from [peers] other devices' files (this device's own excluded). */
        data class Success(val merged: Int, val peers: Int, val needsPairing: Boolean) : Result

        /** No SMB/NAS is configured yet — the user needs to set one up under Recording settings. */
        data object NotConfigured : Result

        /** Anything went wrong reaching or reading the NAS. [message] is best-effort, may be null. */
        data class Failed(val message: String?) : Result
    }

    /**
     * Publish this device's bundle, then merge in every other device's. Safe to call from anywhere:
     * all I/O is on [Dispatchers.IO], a blank/unreachable NAS returns a [Result] rather than
     * throwing, and a single unreadable peer file is skipped rather than failing the whole run.
     */
    suspend fun sync(): Result = withContext(Dispatchers.IO) {
        val config = RecordingStorage.smbConfig(graph.settings)
        if (!config.isConfigured) return@withContext Result.NotConfigured

        runCatching {
            val deviceId = graph.settings.syncDeviceId
            val ownFile = "$deviceId.json"

            // 1. Write this device's bundle. openForWrite creates the sync folder if missing.
            val bundleJson = engine.encode(engine.gather())
            require(bundleJson.toByteArray(Charsets.UTF_8).size <= MAX_SYNC_BUNDLE_BYTES) {
                "Sync bundle exceeds the safety limit."
            }
            SmbClient.writeText(config, locatorFor(config, ownFile), bundleJson)

            // 2. Read and merge every OTHER device's bundle.
            val peerFiles = SmbClient.list(config, syncFolderPath(config))
                .filter { SAFE_SYNC_FILE.matches(it) && it != ownFile }
                .take(MAX_PEER_FILES)

            var merged = SyncEngine.MergeResult()
            var peers = 0
            var needsPairing = false
            val localGroupId = engine.currentGroupId()
            for (name in peerFiles) {
                val text = runCatching {
                    SmbClient.readText(config, locatorFor(config, name), MAX_SYNC_BUNDLE_BYTES)
                }
                    .getOrNull() ?: continue
                // NAS is only a rendezvous. v3/v4 files are not trusted and are deliberately not
                // migrated here; raw URLs and unauthenticated v4 JSON require an explicit pinned
                // TLS or encrypted-backup migration. A secret may never arrive through NAS JSON.
                val bundle = runCatching { engine.decode(text) }.getOrNull() ?: continue
                if (bundle.syncGroupKeyBase64.isNotEmpty()) {
                    needsPairing = true
                    continue
                }
                if (bundle.groupId != localGroupId) {
                    needsPairing = true
                    continue
                }
                val applied = runCatching { engine.apply(bundle, allowKeyTransfer = false) }
                    .getOrNull() ?: continue
                merged += applied
                peers++
            }
            Result.Success(merged.total, peers, needsPairing)
        }.getOrElse { Result.Failed(it.message) }
    }

    /** `<folder>/OpenTV/sync` within the share — the shared rendezvous every device agrees on. */
    private fun syncFolderPath(config: SmbConfig): String {
        val base = config.folder.split('/', '\\').filter { it.isNotBlank() }
        return (base + listOf(SYNC_DIR, "sync")).joinToString("/")
    }

    private fun locatorFor(config: SmbConfig, fileName: String): String =
        "smb://${config.host}/${config.share}/${syncFolderPath(config)}/${fileName.also {
            require(SAFE_SYNC_FILE.matches(it)) { "Invalid sync bundle file name." }
        }}"

    private companion object {
        const val SYNC_DIR = "OpenTV"
        const val MAX_SYNC_BUNDLE_BYTES = 4 * 1024 * 1024
        const val MAX_PEER_FILES = 128
        val SAFE_SYNC_FILE = Regex("[A-Za-z0-9_-]{1,64}\\.json", RegexOption.IGNORE_CASE)
    }
}
