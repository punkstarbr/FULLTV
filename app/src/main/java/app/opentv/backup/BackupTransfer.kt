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
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.data.work.SyncWorker
import app.opentv.sync.SyncEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
        require(passphrase.length >= BackupCryptography.MIN_PASSPHRASE_LENGTH) {
            "Choose a passphrase with at least 8 characters."
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
            syncBundle = SyncEngine(graph).gather(),
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
            var feed = feedDao.byUrl(saved.url)
            if (feed == null && !saved.builtIn) {
                graph.epgRepository.addCustomFeed(saved.name, saved.url)
                feed = feedDao.byUrl(saved.url)
            }
            if (feed != null) {
                feedDao.setEnabled(feed.id, saved.enabled)
                feedsImported++
            }
        }

        val epgResult = graph.epgRepository.syncAll(now, force = true)
        syncFailures += epgResult.feedsFailed
        val merged = SyncEngine(graph).apply(backup.syncBundle)
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
            require(source.url.length <= MAX_URL_LENGTH && isHttpUrl(source.url)) { "Invalid source URL in backup." }
            require(runCatching { SourceKind.valueOf(source.kind) }.isSuccess) { "Unsupported source type." }
        }
        backup.epgFeeds.forEach { feed ->
            require(feed.name.length <= 200 && feed.url.length <= MAX_URL_LENGTH && isHttpUrl(feed.url)) {
                "Invalid EPG feed in backup."
            }
        }
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

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
        recordingTarget = if (recordingTarget.value == AppSettings.RecordingTarget.USB) {
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
        setRecordingTarget(if (target == AppSettings.RecordingTarget.USB) AppSettings.RecordingTarget.INTERNAL else target)
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
        const val MAX_LANGUAGE_TAG_LENGTH = 64
        const val MAX_SETTING_TEXT_LENGTH = 4_096
    }
}

/** Passphrase-based AES-256-GCM container. Authentication failure rejects wrong passwords/tampering. */
internal object BackupCryptography {
    const val MIN_PASSPHRASE_LENGTH = 8
    const val MAX_ENCRYPTED_BYTES = 64 * 1024 * 1024
    private const val MAX_PLAINTEXT_BYTES = 96 * 1024 * 1024
    private const val PBKDF2_ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val HEADER_VERSION = 1
    private val MAGIC = "FULLTVBK".toByteArray(StandardCharsets.US_ASCII)
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) { "Passphrase must contain at least 8 characters." }
        require(plain.size <= MAX_PLAINTEXT_BYTES) { "Backup data is too large." }
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(plain) }
        }.toByteArray()
        require(compressed.size + HEADER_BYTES + GCM_TAG_BYTES <= MAX_ENCRYPTED_BYTES) {
            "Backup file is too large."
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(compressed)
        return ByteArrayOutputStream(HEADER_BYTES + ciphertext.size).apply {
            write(MAGIC)
            write(HEADER_VERSION)
            write(salt)
            write(nonce)
            write(ciphertext)
        }.toByteArray()
    }

    fun decrypt(file: ByteArray, passphrase: String): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) { "Passphrase must contain at least 8 characters." }
        require(file.size in (HEADER_BYTES + GCM_TAG_BYTES)..MAX_ENCRYPTED_BYTES) { "Invalid backup file." }
        require(MAGIC.indices.all { file[it] == MAGIC[it] }) { "This is not a FullTV backup file." }
        require((file[MAGIC.size].toInt() and 0xff) == HEADER_VERSION) { "Unsupported backup file version." }
        val saltStart = MAGIC.size + 1
        val nonceStart = saltStart + SALT_BYTES
        val cipherStart = nonceStart + NONCE_BYTES
        val salt = file.copyOfRange(saltStart, nonceStart)
        val nonce = file.copyOfRange(nonceStart, cipherStart)
        val ciphertext = file.copyOfRange(cipherStart, file.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, nonce))
        val compressed = try {
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Wrong passphrase or damaged backup file.")
        }
        return gunzipLimited(compressed)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
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
    private val HEADER_BYTES = MAGIC.size + 1 + SALT_BYTES + NONCE_BYTES
}
