/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.core.ServiceLocator
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbConfig
import kotlinx.serialization.json.Json

/**
 * Reads local state into a portable bundle and applies a received one. Provider URLs never leave
 * the database as plain text: every cross-device URL identity is a keyed HMAC. The private key is
 * carried only inside pinned TLS sync or the passphrase-encrypted FullTV backup.
 */
class SyncEngine(private val graph: ServiceLocator.Graph) {

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }

    data class MergeResult(
        val positions: Int = 0,
        val favourites: Int = 0,
        val hidden: Int = 0,
        val recordings: Int = 0,
    ) {
        val total: Int get() = positions + favourites + hidden + recordings

        operator fun plus(other: MergeResult): MergeResult = MergeResult(
            positions = positions + other.positions,
            favourites = favourites + other.favourites,
            hidden = hidden + other.hidden,
            recordings = recordings + other.recordings,
        )
    }

    /**
     * [positionsOnly] is used by the LAN Sync screen, which promises to transfer watch positions
     * only. NAS sync and encrypted backups also include curation and shared recording metadata.
     */
    suspend fun gather(
        includeSyncKey: Boolean = false,
        positionsOnly: Boolean = false,
    ): SyncBundle {
        val key = SyncKeyStore.getOrCreate(graph.applicationContext)
        val profiles = graph.profiles.all()
        val names = profiles.associate { it.id to it.name }
        val positions = graph.playbackPositions.all().mapNotNull { pos ->
            val profileName = names[pos.profileId] ?: return@mapNotNull null
            val parts = pos.mediaKey.split(":", limit = 2)
            if (parts.size != 2 || parts[0] !in MEDIA_KINDS) return@mapNotNull null
            val id = parts[1].toLongOrNull() ?: return@mapNotNull null
            val streamUrl = when (parts[0]) {
                "movie" -> graph.catalogRepository.movie(id)?.streamUrl
                "ep" -> graph.catalogRepository.episode(id)?.streamUrl
                else -> null
            } ?: return@mapNotNull null
            SyncPosition(
                profile = profileName,
                mediaKey = SyncPrivacy.mediaKey(key, parts[0], streamUrl),
                kind = parts[0],
                positionMillis = pos.positionMillis,
                durationMillis = pos.durationMillis,
                updatedAtMillis = pos.updatedAtMillis,
            )
        }

        val smbConfig = RecordingStorage.smbConfig(graph.settings)
        val recordings = if (positionsOnly) emptyList() else graph.recordingRepository.all()
            .asSequence()
            .filter { it.status == RecordingStatus.COMPLETED && SmbClient.isSmb(it.filePath) }
            .mapNotNull { recording ->
                val relativePath = runCatching {
                    SmbClient.relativePath(smbConfig, recording.filePath)
                }.getOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                SyncRecording(
                    channelName = recording.channelName.take(MAX_SYNC_TEXT),
                    title = recording.title.take(MAX_SYNC_TEXT),
                    description = recording.description?.take(MAX_SYNC_DESCRIPTION),
                    relativePath = relativePath,
                    scheduledStartMillis = recording.scheduledStartMillis,
                    scheduledEndMillis = recording.scheduledEndMillis,
                    startedAtMillis = recording.startedAtMillis,
                    endedAtMillis = recording.endedAtMillis,
                    sizeBytes = recording.sizeBytes.coerceAtLeast(0),
                )
            }.take(MAX_RECORDINGS).toList()

        val favouriteChannels = if (positionsOnly) emptyList() else graph.catalogRepository
            .favouriteChannelUrls().asSequence()
            .map { SyncPrivacy.mediaKey(key, "channel", it) }
            .distinct().take(MAX_SYNC_IDENTIFIERS).toList()
        val hiddenChannels = if (positionsOnly) emptyList() else graph.catalogRepository
            .hiddenChannelUrls().asSequence()
            .map { SyncPrivacy.mediaKey(key, "channel", it) }
            .distinct().take(MAX_SYNC_IDENTIFIERS).toList()
        val favouriteMovies = if (positionsOnly) emptyList() else graph.catalogRepository
            .favouriteMovieUrls().asSequence()
            .map { SyncPrivacy.mediaKey(key, "movie", it) }
            .distinct().take(MAX_SYNC_IDENTIFIERS).toList()

        val bundle = SyncBundle(
            version = CURRENT_VERSION,
            groupId = SyncPrivacy.groupId(key),
            syncGroupKeyBase64 = if (includeSyncKey) SyncKeyStore.encodeForTransfer(key) else "",
            generatedAtMillis = System.currentTimeMillis(),
            senderDeviceId = graph.settings.syncDeviceId,
            sequence = SyncKeyStore.nextSequence(graph.applicationContext),
            profiles = profiles.map { it.name.take(MAX_SYNC_TEXT) }.distinct().take(MAX_PROFILES),
            positions = positions.take(MAX_POSITIONS),
            favouriteChannels = favouriteChannels,
            hiddenChannels = hiddenChannels,
            favouriteMovies = favouriteMovies,
            favouriteSeries = if (positionsOnly) emptyList() else graph.catalogRepository
                .favouriteSeriesIds().distinct().take(MAX_SYNC_IDENTIFIERS),
            hiddenCategories = if (positionsOnly) emptyList() else graph.settings.hiddenCategories.value
                .asSequence().map { it.take(MAX_SYNC_TEXT) }.distinct().take(MAX_SYNC_IDENTIFIERS).toList(),
            recordings = recordings,
        ).also { validateBundle(it, requireMac = false) }
        return SyncAuthenticator.sign(key, bundle)
    }

    /** Serializes an authenticated bundle; NAS output always strips the secret and re-signs it. */
    fun encode(bundle: SyncBundle, includeSyncKey: Boolean = false): String {
        validateBundle(bundle, requireMac = false)
        val key = SyncKeyStore.getOrCreate(graph.applicationContext)
        val portable = if (includeSyncKey) bundle else bundle.copy(syncGroupKeyBase64 = "")
        if (portable.syncGroupKeyBase64.isNotBlank()) {
            require(SyncKeyStore.decodeTransferKey(portable.syncGroupKeyBase64)?.contentEquals(key) == true) {
                "Only this device's sync-group key may be serialized."
            }
        }
        return json.encodeToString(SyncBundle.serializer(), SyncAuthenticator.sign(key, portable))
    }

    fun currentGroupId(): String =
        SyncPrivacy.groupId(SyncKeyStore.getOrCreate(graph.applicationContext))

    fun decode(bundleJson: String): SyncBundle {
        require(bundleJson.toByteArray(Charsets.UTF_8).size <= MAX_BUNDLE_BYTES) {
            "Sync bundle exceeds the safety limit."
        }
        return json.decodeFromString(SyncBundle.serializer(), bundleJson).also {
            validateBundle(it, requireMac = true)
        }
    }

    /**
     * Explicit migration for a v3 payload received through an authenticated channel. NasSync does
     * not call this: raw-URL files on the NAS are untrusted and are skipped. The returned v5 bundle
     * is immediately re-keyed, assigned a replay identity/counter, and MACed.
     */
    fun migrateLegacyV3(bundleJson: String, smbConfig: SmbConfig): SyncBundle? {
        require(bundleJson.toByteArray(Charsets.UTF_8).size <= MAX_BUNDLE_BYTES)
        val legacy = json.decodeFromString(LegacySyncBundleV3.serializer(), bundleJson)
        if (legacy.version != 3) return null
        require(legacy.profiles.size <= MAX_PROFILES && legacy.positions.size <= MAX_POSITIONS)
        require(legacy.favouriteChannels.size <= MAX_SYNC_IDENTIFIERS)
        require(legacy.hiddenChannels.size <= MAX_SYNC_IDENTIFIERS)
        require(legacy.favouriteMovies.size <= MAX_SYNC_IDENTIFIERS)
        require(legacy.favouriteSeries.size <= MAX_SYNC_IDENTIFIERS)
        require(legacy.hiddenCategories.size <= MAX_SYNC_IDENTIFIERS)
        require(legacy.recordings.size <= MAX_RECORDINGS)

        val key = SyncKeyStore.getOrCreate(graph.applicationContext)
        fun keyed(raw: String, kind: String): String? =
            raw.takeIf { it.isNotBlank() && it.length <= MAX_LEGACY_URL }
                ?.let { SyncPrivacy.mediaKey(key, kind, it) }
        val positions = legacy.positions.mapNotNull { item ->
            if (item.kind !in MEDIA_KINDS || item.profile.isBlank() || item.profile.length > MAX_SYNC_TEXT) {
                return@mapNotNull null
            }
            val id = keyed(item.streamUrl, item.kind) ?: return@mapNotNull null
            SyncPosition(
                profile = item.profile,
                mediaKey = id,
                kind = item.kind,
                positionMillis = item.positionMillis,
                durationMillis = item.durationMillis,
                updatedAtMillis = item.updatedAtMillis,
            )
        }
        val recordings = legacy.recordings.mapNotNull { item ->
            val relativePath = runCatching { SmbClient.relativePath(smbConfig, item.filePath) }
                .getOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            SyncRecording(
                channelName = item.channelName.take(MAX_SYNC_TEXT),
                title = item.title.take(MAX_SYNC_TEXT),
                description = item.description?.take(MAX_SYNC_DESCRIPTION),
                relativePath = relativePath,
                scheduledStartMillis = item.scheduledStartMillis,
                scheduledEndMillis = item.scheduledEndMillis,
                startedAtMillis = item.startedAtMillis,
                endedAtMillis = item.endedAtMillis,
                sizeBytes = item.sizeBytes.coerceAtLeast(0),
            )
        }
        val migrated = SyncBundle(
            version = CURRENT_VERSION,
            groupId = SyncPrivacy.groupId(key),
            generatedAtMillis = legacy.generatedAtMillis.coerceAtLeast(0),
            senderDeviceId = "legacy-v3-${legacy.generatedAtMillis.coerceAtLeast(0)}",
            sequence = SyncKeyStore.nextSequence(graph.applicationContext),
            profiles = legacy.profiles.filter { it.length <= MAX_SYNC_TEXT }.distinct().take(MAX_PROFILES),
            positions = positions,
            favouriteChannels = legacy.favouriteChannels.mapNotNull { keyed(it, "channel") }.distinct(),
            hiddenChannels = legacy.hiddenChannels.mapNotNull { keyed(it, "channel") }.distinct(),
            favouriteMovies = legacy.favouriteMovies.mapNotNull { keyed(it, "movie") }.distinct(),
            favouriteSeries = legacy.favouriteSeries.filter { it.isNotBlank() && it.length <= MAX_SYNC_TEXT }.distinct(),
            hiddenCategories = legacy.hiddenCategories.filter { it.length <= MAX_SYNC_TEXT }.distinct(),
            recordings = recordings,
        ).also { validateBundle(it, requireMac = false) }
        return SyncAuthenticator.sign(key, migrated)
    }

    /**
     * Applies only an authenticated v5 bundle. Existing LAN and encrypted-backup callers use the
     * trusted default; NAS callers must pass false because NAS JSON can never introduce a key.
     */
    suspend fun apply(bundle: SyncBundle, allowKeyTransfer: Boolean = true): MergeResult {
        val currentKey = SyncKeyStore.getOrCreate(graph.applicationContext)
        val prepared = if (bundle.version == LEGACY_AUTHENTICATED_VERSION && allowKeyTransfer) {
            migrateAuthenticatedLegacyV4(bundle, currentKey)
        } else {
            bundle
        }
        validateBundle(prepared, requireMac = true)
        val transferredKey = prepared.syncGroupKeyBase64.takeIf(String::isNotBlank)?.let { encoded ->
            SyncKeyStore.decodeTransferKey(encoded)
                ?: throw IllegalArgumentException("Sync-group key is invalid.")
        }
        require(transferredKey == null || allowKeyTransfer) {
            "A sync-group key may only arrive over pinned TLS or an authenticated backup."
        }
        val key = transferredKey ?: currentKey
        require(prepared.groupId == SyncPrivacy.groupId(key)) {
            "This NAS sync bundle belongs to another sync group. Pair the devices once over pinned HTTPS first."
        }
        require(SyncAuthenticator.verify(key, prepared)) {
            "Sync bundle authentication failed; no fields were applied."
        }
        check(SyncKeyStore.acceptSequence(graph.applicationContext, prepared.senderDeviceId, prepared.sequence)) {
            "Sync bundle replay or out-of-order counter rejected."
        }
        // A transported key can only arrive from pinned HTTPS SyncClient or the encrypted backup.
        // Adopt it only after structural, group, MAC, and replay validation.
        if (transferredKey != null && !currentKey.contentEquals(transferredKey)) {
            SyncKeyStore.replace(graph.applicationContext, transferredKey)
        }

        for (name in prepared.profiles) {
            if (name.isNotBlank() && graph.profiles.byName(name) == null) {
                runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }
            }
        }

        val channelKeysNeeded = (prepared.favouriteChannels + prepared.hiddenChannels).toHashSet()
        val channelUrlByKey = if (channelKeysNeeded.isEmpty()) emptyMap() else
            graph.catalogRepository.allChannelStreamUrls().asSequence().filter(String::isNotBlank).distinct()
                .associateBy { SyncPrivacy.mediaKey(key, "channel", it) }
        val movieKeysNeeded = prepared.favouriteMovies.toHashSet()
        val positionMovieKeys = prepared.positions.filter { it.kind == "movie" }.map { it.mediaKey }
        val movieUrlByKey = if (movieKeysNeeded.isEmpty() && positionMovieKeys.isEmpty()) emptyMap() else
            graph.catalogRepository.allMovieStreamUrls().asSequence().filter(String::isNotBlank).distinct()
                .associateBy { SyncPrivacy.mediaKey(key, "movie", it) }
        val positionEpisodeKeys = prepared.positions.filter { it.kind == "ep" }.map { it.mediaKey }
        val episodeUrlByKey = if (positionEpisodeKeys.isEmpty()) emptyMap() else
            graph.catalogRepository.allEpisodeStreamUrls().asSequence().filter(String::isNotBlank).distinct()
                .associateBy { SyncPrivacy.mediaKey(key, "ep", it) }

        val localFavChannels = graph.catalogRepository.favouriteChannelUrls()
            .map { SyncPrivacy.mediaKey(key, "channel", it) }.toHashSet()
        val localHiddenChannels = graph.catalogRepository.hiddenChannelUrls()
            .map { SyncPrivacy.mediaKey(key, "channel", it) }.toHashSet()
        val localFavMovies = graph.catalogRepository.favouriteMovieUrls()
            .map { SyncPrivacy.mediaKey(key, "movie", it) }.toHashSet()
        val localFavSeries = graph.catalogRepository.favouriteSeriesIds().toHashSet()

        var favourites = 0
        var hidden = 0
        for (remoteKey in prepared.favouriteChannels) {
            val url = channelUrlByKey[remoteKey] ?: continue
            graph.catalogRepository.markChannelFavouriteByUrl(url)
            if (localFavChannels.add(remoteKey)) favourites++
        }
        for (remoteKey in prepared.hiddenChannels) {
            val url = channelUrlByKey[remoteKey] ?: continue
            graph.catalogRepository.markChannelHiddenByUrl(url)
            if (localHiddenChannels.add(remoteKey)) hidden++
        }
        for (remoteKey in prepared.favouriteMovies) {
            val url = movieUrlByKey[remoteKey] ?: continue
            graph.catalogRepository.markMovieFavouriteByUrl(url)
            if (localFavMovies.add(remoteKey)) favourites++
        }
        for (seriesId in prepared.favouriteSeries) {
            graph.catalogRepository.markSeriesFavouriteBySeriesId(seriesId)
            if (localFavSeries.add(seriesId)) favourites++
        }
        if (prepared.hiddenCategories.isNotEmpty()) {
            graph.settings.setHiddenCategories(graph.settings.hiddenCategories.value + prepared.hiddenCategories)
        }

        var recordings = 0
        val smbConfig = RecordingStorage.smbConfig(graph.settings)
        for (rec in prepared.recordings) {
            val locator = runCatching { SmbClient.locatorForRelativePath(smbConfig, rec.relativePath) }
                .getOrNull() ?: continue
            if (!SmbClient.isValidLocator(smbConfig, locator)) continue
            if (graph.recordingRepository.byFilePath(locator) != null) continue
            val inserted = runCatching {
                graph.recordingRepository.insert(
                    Recording(
                        channelId = 0,
                        sourceId = 0,
                        channelName = rec.channelName,
                        logoUrl = null,
                        title = rec.title,
                        description = rec.description,
                        filePath = locator,
                        // A completed shared recording plays from its NAS file; the original
                        // credential-bearing provider URL and User-Agent are not needed.
                        streamUrl = "",
                        userAgent = "",
                        scheduledStartMillis = rec.scheduledStartMillis,
                        scheduledEndMillis = rec.scheduledEndMillis,
                        startedAtMillis = rec.startedAtMillis,
                        endedAtMillis = rec.endedAtMillis,
                        status = RecordingStatus.COMPLETED,
                        sizeBytes = rec.sizeBytes.coerceAtLeast(0),
                    ),
                )
            }.isSuccess
            if (inserted) recordings++
        }

        var positions = 0
        for (item in prepared.positions) {
            val profileId = profileIdFor(item.profile) ?: continue
            val url = when (item.kind) {
                "movie" -> movieUrlByKey[item.mediaKey]
                "ep" -> episodeUrlByKey[item.mediaKey]
                else -> null
            } ?: continue
            val localId = when (item.kind) {
                "movie" -> graph.catalogRepository.movieByStreamUrl(url)?.id
                "ep" -> graph.catalogRepository.episodeByStreamUrl(url)?.id
                else -> null
            } ?: continue
            val mediaKey = "${item.kind}:$localId"
            val existing = graph.playbackPositions.get(profileId, mediaKey)
            if (existing == null || item.updatedAtMillis > existing.updatedAtMillis) {
                graph.playbackPositions.upsert(
                    PlaybackPosition(
                        profileId = profileId,
                        mediaKey = mediaKey,
                        positionMillis = item.positionMillis,
                        durationMillis = item.durationMillis,
                        updatedAtMillis = item.updatedAtMillis,
                    ),
                )
                positions++
            }
        }

        return MergeResult(positions, favourites, hidden, recordings)
    }

    /** v4 has keyed identifiers but no payload MAC; only TLS/backup callers may invoke this path. */
    private fun migrateAuthenticatedLegacyV4(bundle: SyncBundle, currentKey: ByteArray): SyncBundle {
        require(bundle.version == LEGACY_AUTHENTICATED_VERSION)
        val transferredKey = bundle.syncGroupKeyBase64.takeIf(String::isNotBlank)?.let { encoded ->
            SyncKeyStore.decodeTransferKey(encoded)
                ?: throw IllegalArgumentException("Sync-group key is invalid.")
        }
        val key = transferredKey ?: currentKey
        require(bundle.groupId == SyncPrivacy.groupId(key)) {
            "The authenticated legacy bundle belongs to another sync group."
        }
        val migrated = bundle.copy(
            version = CURRENT_VERSION,
            senderDeviceId = "legacy-v4-${SyncAuthenticator.legacyIdentity(bundle)}",
            sequence = SyncKeyStore.nextSequence(graph.applicationContext),
            mac = "",
        )
        validateBundle(migrated, requireMac = false)
        return SyncAuthenticator.sign(key, migrated)
    }

    private suspend fun profileIdFor(name: String): Long? {
        graph.profiles.byName(name)?.let { return it.id }
        return runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }.getOrNull()
    }

    private fun validateBundle(bundle: SyncBundle, requireMac: Boolean) {
        require(bundle.version == CURRENT_VERSION) {
            "Only authenticated sync bundle version $CURRENT_VERSION is accepted."
        }
        require(bundle.groupId.matches(Regex("[0-9a-f]{24}"))) { "Sync bundle group id is invalid." }
        require(bundle.syncGroupKeyBase64.length <= MAX_KEY_TEXT)
        require(bundle.generatedAtMillis >= 0L)
        require(bundle.senderDeviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "Sync bundle sender identity is invalid."
        }
        require(bundle.sequence > 0L) { "Sync bundle sequence is invalid." }
        if (requireMac) {
            require(bundle.mac.matches(MAC_PATTERN)) { "Sync bundle MAC is invalid." }
        } else {
            require(bundle.mac.isEmpty() || bundle.mac.matches(MAC_PATTERN)) {
                "Sync bundle MAC is invalid."
            }
        }
        require(bundle.profiles.size <= MAX_PROFILES && bundle.positions.size <= MAX_POSITIONS)
        require(bundle.favouriteChannels.size <= MAX_SYNC_IDENTIFIERS)
        require(bundle.hiddenChannels.size <= MAX_SYNC_IDENTIFIERS)
        require(bundle.favouriteMovies.size <= MAX_SYNC_IDENTIFIERS)
        require(bundle.favouriteSeries.size <= MAX_SYNC_IDENTIFIERS)
        require(bundle.hiddenCategories.size <= MAX_SYNC_IDENTIFIERS)
        require(bundle.recordings.size <= MAX_RECORDINGS)
        bundle.profiles.forEach { require(it.length <= MAX_SYNC_TEXT) }
        bundle.positions.forEach {
            require(it.profile.isNotBlank() && it.profile.length <= MAX_SYNC_TEXT)
            require(it.kind in MEDIA_KINDS)
            require(it.mediaKey.matches(Regex("[0-9a-f]{64}")))
            require(it.positionMillis >= 0 && it.durationMillis >= 0 && it.updatedAtMillis >= 0)
        }
        (bundle.favouriteChannels + bundle.hiddenChannels + bundle.favouriteMovies).forEach {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }
        bundle.favouriteSeries.forEach { require(it.isNotBlank() && it.length <= MAX_SYNC_TEXT) }
        bundle.hiddenCategories.forEach { require(it.isNotBlank() && it.length <= MAX_SYNC_TEXT) }
        bundle.recordings.forEach {
            require(it.channelName.length <= MAX_SYNC_TEXT && it.title.length <= MAX_SYNC_TEXT)
            require((it.description?.length ?: 0) <= MAX_SYNC_DESCRIPTION)
            require(it.relativePath.length in 1..MAX_SYNC_PATH)
            require(!it.relativePath.startsWith('/') && !it.relativePath.startsWith('\\'))
            require(
                it.scheduledStartMillis >= 0L && it.scheduledEndMillis >= 0L &&
                    it.startedAtMillis >= 0L && it.endedAtMillis >= 0L && it.sizeBytes >= 0L,
            )
        }
    }

    private companion object {
        const val CURRENT_VERSION = 5
        const val LEGACY_AUTHENTICATED_VERSION = 4
        const val MAX_BUNDLE_BYTES = 8 * 1024 * 1024
        const val MAX_KEY_TEXT = 128
        const val MAX_PROFILES = 100
        const val MAX_POSITIONS = 100_000
        const val MAX_SYNC_IDENTIFIERS = 200_000
        const val MAX_RECORDINGS = 10_000
        const val MAX_SYNC_TEXT = 256
        const val MAX_SYNC_DESCRIPTION = 4_096
        const val MAX_SYNC_PATH = 1_024
        const val MAX_LEGACY_URL = 8_192
        val MAC_PATTERN = Regex("[0-9a-f]{64}")
        val MEDIA_KINDS = setOf("movie", "ep")
    }
}
