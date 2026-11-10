/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.backup

import android.content.Context
import android.net.Uri
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.HttpsUrlPolicy
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.data.work.SyncWorker
import app.opentv.sync.SyncEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local, user-directed file backup. No backup bytes or passphrases are sent to an OpenTV server. */
class BackupTransfer(
    private val context: Context,
    private val graph: ServiceLocator.Graph,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(uri: Uri, passphrase: String) {
        require(passphrase.length >= BackupCryptography.MIN_NEW_PASSPHRASE_LENGTH) {
            "Choose a passphrase with at least 12 characters."
        }
        val bundle = FullTvBackup(
            createdAtMillis = System.currentTimeMillis(),
            sources = graph.database.sources().all().map { source ->
                BackupSource(
                    name = source.name,
                    kind = source.kind.name,
                    url = source.url,
                    username = source.username,
                    password = source.password,
                    macAddress = source.macAddress,
                    epgUrl = source.epgUrl,
                    userAgent = source.userAgent,
                    liveFormat = source.liveFormat.name,
                    enabled = source.enabled,
                )
            },
            epgFeeds = graph.database.epgFeeds().all()
                .filter { it.url != null && it.providerSourceId == null && it.playlistSourceId == null }
                .map { feed ->
                    BackupEpgFeed(
                        name = feed.name,
                        url = feed.url.orEmpty(),
                        enabled = feed.enabled,
                        builtIn = feed.builtIn,
                    )
                },
            settings = graph.settings.toBackupSettings(),
            // The shared HMAC key is safe here because the entire backup is encrypted with the
            // user's passphrase before it leaves this device.
            syncBundle = SyncEngine(graph).gather(includeSyncKey = true),
        )
        val plain = json.encodeToString(bundle).toByteArray(StandardCharsets.UTF_8)
        val encrypted = withContext(Dispatchers.IO) { BackupCryptography.encrypt(plain, passphrase) }
        withContext(Dispatchers.IO) {
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: error("Could not open the selected backup destination.")
            output.use { it.write(encrypted) }
        }
    }

    /** Imports settings and sources, refreshes their catalogue/guide, then merges portable state. */
    suspend fun restore(uri: Uri, passphrase: String): BackupRestoreResult {
        require(passphrase.length >= BackupCryptography.MIN_PASSPHRASE_LENGTH) {
            "Enter the passphrase used to create the backup."
        }
        val encrypted = withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not open the selected backup file.")
            input.use { readLimited(it, BackupCryptography.MAX_ENCRYPTED_BYTES) }
        }
        val plain = withContext(Dispatchers.IO) { BackupCryptography.decrypt(encrypted, passphrase) }
        val backup = json.decodeFromString<FullTvBackup>(plain.toString(StandardCharsets.UTF_8))
        require(backup.version == FullTvBackup.CURRENT_VERSION) { "Unsupported backup version." }
        validate(backup)

        graph.settings.applyBackupSettings(backup.settings)
        SyncWorker.schedule(context)

        val sourceDao = graph.database.sources()
        val existingByKey = sourceDao.all().associateBy { sourceKey(it.kind, it.url) }.toMutableMap()
        val importedSources = ArrayList<Source>()
        var syncFailures = 0
        val now = System.currentTimeMillis()

        for (saved in backup.sources) {
            val kind = SourceKind.valueOf(saved.kind)
            val liveFormat = runCatching { LiveStreamFormat.valueOf(saved.liveFormat) }
                .getOrDefault(LiveStreamFormat.HLS)
            val normalizedUrl = SourceRepository.normaliseUrl(saved.url, kind)
            val key = sourceKey(kind, normalizedUrl)
            val existing = existingByKey[key]
            val source = Source(
                id = existing?.id ?: 0L,
                name = saved.name,
                kind = kind,
                url = normalizedUrl,
                username = saved.username,
                password = saved.password,
                macAddress = saved.macAddress,
                epgUrl = saved.epgUrl,
                userAgent = saved.userAgent,
                liveFormat = liveFormat,
                enabled = saved.enabled,
                lastCatalogSyncMillis = 0L,
            )
            val id = graph.sourceRepository.save(source)
            val restored = graph.sourceRepository.byId(id) ?: continue
            existingByKey[key] = restored
            importedSources += restored
            if (restored.enabled) {
                try {
                    if (graph.catalogRepository.sync(restored, now) is CatalogRepository.SyncResult.Failed) {
                        syncFailures++
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    syncFailures++
                }
            }
        }

        graph.epgRepository.ensureFeeds()
        val feedDao = graph.database.epgFeeds()
        var feedsImported = 0
        for (saved in backup.epgFeeds) {
            var feed = feedDao.byGlobalUrl(saved.url)
            if (feed == null && !saved.builtIn) {
                graph.epgRepository.addCustomFeed(saved.name, saved.url)
                feed = feedDao.byGlobalUrl(saved.url)
            }
            if (feed != null) {
                feedDao.setEnabled(feed.id, saved.enabled)
                feedsImported++
            }
        }

        val epgResult = graph.epgRepository.syncAll(now, force = true)
        syncFailures += epgResult.feedsFailed
        // Version 3 sync bundles contained raw stream URLs. Preserve the rest of an old backup,
        // but deliberately skip that unsafe nested snapshot during restore.
        val merged = if (backup.syncBundle.version < 4 || backup.syncBundle.groupId.isBlank()) SyncEngine.MergeResult()
        else SyncEngine(graph).apply(backup.syncBundle)
        runCatching { graph.recordingEngine.rescanSeriesRules() }
        return BackupRestoreResult(
            sourcesImported = importedSources.size,
            feedsImported = feedsImported,
            syncFailures = syncFailures,
            mergedItems = merged.total,
        )
    }

    private fun validate(backup: FullTvBackup) {
        require(backup.sources.size <= MAX_SOURCES) { "Backup contains too many sources." }
        require(backup.epgFeeds.size <= MAX_FEEDS) { "Backup contains too many EPG feeds." }
        backup.sources.forEach { source ->
            require(source.name.isNotBlank() && source.name.length <= 200) { "Invalid source name in backup." }
            require(source.url.length <= MAX_URL_LENGTH) { "Invalid source URL in backup." }
            val kind = runCatching { SourceKind.valueOf(source.kind) }
                .getOrElse { throw IllegalArgumentException("Unsupported source type.") }
            HttpsUrlPolicy.requireHttps(SourceRepository.normaliseUrl(source.url, kind))
            require(source.username.orEmpty().length <= MAX_CREDENTIAL_LENGTH &&
                source.password.orEmpty().length <= MAX_CREDENTIAL_LENGTH &&
                source.macAddress.orEmpty().length <= MAX_MAC_LENGTH &&
                source.userAgent.length <= MAX_USER_AGENT_LENGTH
            ) { "Source credentials or User-Agent exceed the safety limit." }
            source.epgUrl?.let(HttpsUrlPolicy::requireHttps)
        }
        backup.epgFeeds.forEach { feed ->
            require(feed.name.length <= 200 && feed.url.length <= MAX_URL_LENGTH) {
                "Invalid EPG feed in backup."
            }
            HttpsUrlPolicy.requireHttps(feed.url)
        }
    }

    private fun sourceKey(kind: SourceKind, url: String): String =
        "${kind.name}:${SourceRepository.normaliseUrl(url, kind)}"

    private fun AppSettings.toBackupSettings() = BackupSettings(
        themeMode = themeMode.value.name,
        channelLayout = channelLayout.value.name,
        interfaceStyle = interfaceStyle.value.name,
        guideDensity = guideDensity.value.name,
        languageTag = languageTag.value,
        subtitlesEnabled = subtitlesEnabled.value,
        autoPortugueseSubtitles = autoPortugueseSubtitles.value,
        guidePreviewVideo = guidePreviewVideo.value,
        guidePreviewSound = guidePreviewSound.value,
        resumeLastChannel = resumeLastChannel.value,
        catalogAutoRefresh = catalogAutoRefresh.value,
        catalogRefreshIntervalHours = catalogRefreshIntervalHours.value,
        catalogAskOnStart = catalogAskOnStart.value,
        liveEnabled = liveEnabled.value,
        moviesEnabled = moviesEnabled.value,
        seriesEnabled = seriesEnabled.value,
        manualRecordMinutes = manualRecordMinutes.value,
        recordPadStartMinutes = recordPadStartMinutes.value,
        recordPadEndMinutes = recordPadEndMinutes.value,
        recordScheduleLookaheadDays = recordScheduleLookaheadDays.value,
        recordAutoSwitch = recordAutoSwitch.value,
        livePauseEnabled = livePauseEnabled.value,
        playerResizeMode = playerResizeMode.value,
        recordingContainer = recordingContainer.value.name,
        // USB grants and local-only FTP credentials are device-specific and are never transferred.
        recordingTarget = if (recordingTarget.value == AppSettings.RecordingTarget.USB ||
            recordingTarget.value == AppSettings.RecordingTarget.FTP
        ) {
            AppSettings.RecordingTarget.INTERNAL.name
        } else recordingTarget.value.name,
        smbHost = smbHost.value,
        smbShare = smbShare.value,
        smbFolder = smbFolder.value,
        smbUser = smbUser.value,
        smbPassword = smbPassword.value,
        nasAutoSync = nasAutoSync.value,
        tmdbApiKey = tmdbApiKey.value,
    )

    private fun AppSettings.applyBackupSettings(saved: BackupSettings) {
        runCatching { AppSettings.ThemeMode.valueOf(saved.themeMode) }
            .getOrNull()?.let(::setThemeMode)
        runCatching { AppSettings.ChannelLayout.valueOf(saved.channelLayout) }
            .getOrNull()?.let(::setChannelLayout)
        runCatching { AppSettings.InterfaceStyle.valueOf(saved.interfaceStyle) }
            .getOrNull()?.let(::setInterfaceStyle)
        runCatching { AppSettings.GuideDensity.valueOf(saved.guideDensity) }
            .getOrNull()?.let(::setGuideDensity)
        setLanguageTag(saved.languageTag.take(MAX_LANGUAGE_TAG_LENGTH))
        setSubtitlesEnabled(saved.subtitlesEnabled)
        setAutoPortugueseSubtitles(saved.autoPortugueseSubtitles)
        setGuidePreviewVideo(saved.guidePreviewVideo)
        setGuidePreviewSound(saved.guidePreviewSound)
        setResumeLastChannel(saved.resumeLastChannel)
        setCatalogAutoRefresh(saved.catalogAutoRefresh)
        setCatalogRefreshIntervalHours(saved.catalogRefreshIntervalHours)
        setCatalogAskOnStart(saved.catalogAskOnStart)
        setLiveEnabled(saved.liveEnabled)
        setMoviesEnabled(saved.moviesEnabled)
        setSeriesEnabled(saved.seriesEnabled)
        setManualRecordMinutes(saved.manualRecordMinutes)
        setRecordPadding(saved.recordPadStartMinutes, saved.recordPadEndMinutes)
        setRecordScheduleLookaheadDays(saved.recordScheduleLookaheadDays)
        setRecordAutoSwitch(saved.recordAutoSwitch)
        setLivePauseEnabled(saved.livePauseEnabled)
        setPlayerResizeMode(saved.playerResizeMode)
        runCatching { AppSettings.RecordingContainer.valueOf(saved.recordingContainer) }
            .getOrNull()?.let(::setRecordingContainer)
        val target = runCatching { AppSettings.RecordingTarget.valueOf(saved.recordingTarget) }
            .getOrDefault(AppSettings.RecordingTarget.INTERNAL)
        setRecordingTarget(
            if (target == AppSettings.RecordingTarget.USB || target == AppSettings.RecordingTarget.FTP) {
                AppSettings.RecordingTarget.INTERNAL
            } else target,
        )
        setSmbConfig(
            saved.smbHost.take(MAX_SETTING_TEXT_LENGTH),
            saved.smbShare.take(MAX_SETTING_TEXT_LENGTH),
            saved.smbFolder.take(MAX_SETTING_TEXT_LENGTH),
            saved.smbUser.take(MAX_SETTING_TEXT_LENGTH),
            saved.smbPassword.take(MAX_SETTING_TEXT_LENGTH),
        )
        setNasAutoSync(saved.nasAutoSync)
        setTmdbApiKey(saved.tmdbApiKey.take(MAX_SETTING_TEXT_LENGTH))
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "Backup file is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_SOURCES = 100
        const val MAX_FEEDS = 500
        const val MAX_URL_LENGTH = 8_192
        const val MAX_CREDENTIAL_LENGTH = 4_096
        const val MAX_MAC_LENGTH = 64
        const val MAX_USER_AGENT_LENGTH = 1_024
        const val MAX_LANGUAGE_TAG_LENGTH = 64
        const val MAX_SETTING_TEXT_LENGTH = 4_096
    }
}

/** Passphrase-based AES-256-GCM container. Authentication failure rejects wrong passwords/tampering. */
internal object BackupCryptography {
    const val MIN_PASSPHRASE_LENGTH = 8
    const val MIN_NEW_PASSPHRASE_LENGTH = 12
    const val MAX_ENCRYPTED_BYTES = 64 * 1024 * 1024
    private const val MAX_PLAINTEXT_BYTES = 96 * 1024 * 1024
    private const val CURRENT_PBKDF2_ITERATIONS = 600_000
    private const val LEGACY_PBKDF2_ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HEADER_VERSION = 2
    private const val ITERATIONS_BYTES = 4
    private val MAGIC = "FULLTVBK".toByteArray(StandardCharsets.US_ASCII)
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        require(passphrase.length >= MIN_NEW_PASSPHRASE_LENGTH) { "Passphrase must contain at least 12 characters." }
        require(plain.size <= MAX_PLAINTEXT_BYTES) { "Backup data is too large." }
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(plain) }
        }.toByteArray()
        require(compressed.size + HEADER_BYTES + GCM_TAG_BYTES <= MAX_ENCRYPTED_BYTES) {
            "Backup file is too large."
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = ByteArrayOutputStream(HEADER_BYTES).apply {
            write(MAGIC)
            write(HEADER_VERSION)
            write(ByteBuffer.allocate(ITERATIONS_BYTES).putInt(CURRENT_PBKDF2_ITERATIONS).array())
            write(salt)
            write(nonce)
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(passphrase, salt, CURRENT_PBKDF2_ITERATIONS),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(compressed)
        return ByteArrayOutputStream(HEADER_BYTES + ciphertext.size).apply {
            write(header)
            write(ciphertext)
        }.toByteArray()
    }

    fun decrypt(file: ByteArray, passphrase: String): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) { "Passphrase must contain at least 8 characters." }
        require(file.size in (MIN_HEADER_BYTES + GCM_TAG_BYTES)..MAX_ENCRYPTED_BYTES) { "Invalid backup file." }
        require(MAGIC.indices.all { file[it] == MAGIC[it] }) { "This is not a FullTV backup file." }
        val version = file[MAGIC.size].toInt() and 0xff
        val (iterations, saltStart) = when (version) {
            1 -> LEGACY_PBKDF2_ITERATIONS to (MAGIC.size + 1)
            HEADER_VERSION -> {
                require(file.size >= HEADER_BYTES + GCM_TAG_BYTES) { "Invalid backup file." }
                val iterationsOffset = MAGIC.size + 1
                val storedIterations = ByteBuffer.wrap(file, iterationsOffset, ITERATIONS_BYTES).int
                require(storedIterations in CURRENT_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
                    "Unsupported backup security parameters."
                }
                storedIterations to (iterationsOffset + ITERATIONS_BYTES)
            }
            else -> throw IllegalArgumentException("Unsupported backup file version.")
        }
        val nonceStart = saltStart + SALT_BYTES
        val cipherStart = nonceStart + NONCE_BYTES
        val salt = file.copyOfRange(saltStart, nonceStart)
        val nonce = file.copyOfRange(nonceStart, cipherStart)
        val ciphertext = file.copyOfRange(cipherStart, file.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, nonce))
        if (version == HEADER_VERSION) cipher.updateAAD(file.copyOfRange(0, cipherStart))
        val compressed = try {
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Wrong passphrase or damaged backup file.")
        }
        return gunzipLimited(compressed)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun gunzipLimited(compressed: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_PLAINTEXT_BYTES) { "Backup data is too large." }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private const val GCM_TAG_BYTES = 16
    private const val MAX_PBKDF2_ITERATIONS = 2_000_000
    private val MIN_HEADER_BYTES = MAGIC.size + 1 + SALT_BYTES + NONCE_BYTES
    private val HEADER_BYTES = MAGIC.size + 1 + ITERATIONS_BYTES + SALT_BYTES + NONCE_BYTES
}
