/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.os.SystemClock
import app.opentv.data.model.StremioAddon
import app.opentv.security.EncryptedSecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The handful of user preferences that are not "data" (sources, guides) but "how the app
 * behaves". Backed by [android.content.SharedPreferences] rather than DataStore on purpose:
 * three flags read at composition time do not need an async, Flow-based store and the
 * ceremony that comes with it. Each setting is also mirrored into a [StateFlow] so Compose
 * recomposes the moment one changes.
 */
class AppSettings private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
    private val secretStore = EncryptedSecretStore(context)

    private val addonJson = Json { ignoreUnknownKeys = true }

    /** How the app chooses light vs dark. TV defaults to dark under [ThemeMode.SYSTEM]. */
    enum class ThemeMode { SYSTEM, DARK, LIGHT }

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * How the live-TV channel list is laid out: the scrolling EPG time-[ChannelLayout.GRID], or a
     * compact one-channel-per-row [ChannelLayout.LIST] (logo, name, now/next) for people who find
     * the full grid busy. Defaults to the grid.
     */
    enum class ChannelLayout { GRID, LIST }

    private val _channelLayout = MutableStateFlow(readChannelLayout())
    val channelLayout: StateFlow<ChannelLayout> = _channelLayout.asStateFlow()

    /** Visual preset for Live TV and the player. Other library/settings screens stay unchanged. */
    enum class InterfaceStyle {
        CURRENT,
        PLAYER_ZAPPING,
        CLASSIC_EPG,
        CATEGORY_PORTAL,
        MODERN_EPG,
        RETRO_EPG,
        OI_HYBRID,
        PLAYER_INFO_TOP,
        EPG_FULLSCREEN,
        LINEAR_AUTOHIDE,
        BOTTOM_INFO_DRAWER,
    }

    private val _interfaceStyle = MutableStateFlow(readInterfaceStyle())
    val interfaceStyle: StateFlow<InterfaceStyle> = _interfaceStyle.asStateFlow()

    /** Whether to keep the device's local clock visible over every app screen. */
    private val _persistentClock = MutableStateFlow(prefs.getBoolean(KEY_PERSISTENT_CLOCK, false))
    val persistentClock: StateFlow<Boolean> = _persistentClock.asStateFlow()

    /**
     * How tall the guide rows are and how much time fits on screen. On a weak box [COMPACT] packs
     * more channels in and makes each programme narrower, so fewer blocks are laid out per row.
     */
    enum class GuideDensity { COMPACT, NORMAL, COMFORTABLE }

    private val _guideDensity = MutableStateFlow(readGuideDensity())
    val guideDensity: StateFlow<GuideDensity> = _guideDensity.asStateFlow()

    /** Whether embedded subtitles/closed captions are shown when a stream carries them. */
    private val _subtitlesEnabled = MutableStateFlow(prefs.getBoolean(KEY_SUBTITLES, true))
    val subtitlesEnabled: StateFlow<Boolean> = _subtitlesEnabled.asStateFlow()

    /** Whether text subtitle cues supplied by channels are translated to Portuguese on-device. */
    private val _autoPortugueseSubtitles =
        MutableStateFlow(prefs.getBoolean(KEY_AUTO_PT_SUBTITLES, false))
    val autoPortugueseSubtitles: StateFlow<Boolean> = _autoPortugueseSubtitles.asStateFlow()

    /** Whether the selected channel plays live inside the guide's preview pane. */
    private val _guidePreviewVideo = MutableStateFlow(prefs.getBoolean(KEY_PREVIEW_VIDEO, true))
    val guidePreviewVideo: StateFlow<Boolean> = _guidePreviewVideo.asStateFlow()

    /** Whether the guide preview plays sound (off by default — quieter while browsing). */
    private val _guidePreviewSound = MutableStateFlow(prefs.getBoolean(KEY_PREVIEW_SOUND, false))
    val guidePreviewSound: StateFlow<Boolean> = _guidePreviewSound.asStateFlow()

    /** The profile whose watch history is active. Defaults to the built-in profile (id 1). */
    private val _activeProfileId = MutableStateFlow(prefs.getLong(KEY_ACTIVE_PROFILE, 1L))
    val activeProfileId: StateFlow<Long> = _activeProfileId.asStateFlow()

    fun setActiveProfile(id: Long) {
        prefs.edit().putLong(KEY_ACTIVE_PROFILE, id).apply()
        _activeProfileId.value = id
    }

    // ---- Parental controls -------------------------------------------------------------------

    /** Whether a parental PIN is set. Its verifier is a Keystore-keyed HMAC, never the PIN. */
    private val _pinIsSet = MutableStateFlow(prefs.getString(KEY_PIN_HASH, null) != null)
    val pinIsSet: StateFlow<Boolean> = _pinIsSet.asStateFlow()

    /** Category group keys the user has marked adult/hidden. */
    private val _hiddenCategories =
        MutableStateFlow(prefs.getStringSet(KEY_HIDDEN_CATS, emptySet())!!.toSet())
    val hiddenCategories: StateFlow<Set<String>> = _hiddenCategories.asStateFlow()

    /**
     * Session unlock. Deliberately *not* persisted: revealing hidden categories lasts until the
     * app is next launched, so a child restarting the app is back behind the lock.
     */
    private val _hiddenUnlocked = MutableStateFlow(false)
    val hiddenUnlocked: StateFlow<Boolean> = _hiddenUnlocked.asStateFlow()

    fun setPin(pin: String): Boolean = synchronized(PIN_LOCK) {
        require(pin.length == PIN_LENGTH && pin.all(Char::isDigit)) { "PIN must contain four digits." }
        val previousPinHash = prefs.getString(KEY_PIN_HASH, null)
        val saved = prefs.edit()
            .putString(KEY_PIN_HASH, PIN_HMAC_PREFIX + hashPin(pin))
            .remove(KEY_PIN_FAILURES)
            .remove(KEY_PIN_LOCK_UNTIL)
            .remove(KEY_PIN_LOCK_UNTIL_ELAPSED)
            .remove(KEY_PIN_LOCK_DURATION)
            .remove(KEY_PIN_LAST_ELAPSED)
            .commit()
        if (!saved && previousPinHash != null) {
            runCatching { prefs.edit().putString(KEY_PIN_HASH, previousPinHash).commit() }
        }
        _pinIsSet.value = prefs.getString(KEY_PIN_HASH, null) != null
        saved
    }

    fun clearPin(): Boolean = synchronized(PIN_LOCK) {
        val previousPinHash = prefs.getString(KEY_PIN_HASH, null)
        if (previousPinHash == null) {
            if (_pinIsSet.value) return@synchronized false
            _pinIsSet.value = false
            _hiddenUnlocked.value = true
            return@synchronized true
        }
        val saved = prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_FAILURES)
            .remove(KEY_PIN_LOCK_UNTIL)
            .remove(KEY_PIN_LOCK_UNTIL_ELAPSED)
            .remove(KEY_PIN_LOCK_DURATION)
            .remove(KEY_PIN_LAST_ELAPSED)
            .commit()
        if (!saved) {
            // SharedPreferences may already have changed its in-memory map even when disk commit
            // fails. Restore the prior verifier where possible and remain locked either way.
            runCatching { prefs.edit().putString(KEY_PIN_HASH, previousPinHash).commit() }
            _pinIsSet.value = true
            return@synchronized false
        }
        _pinIsSet.value = false
        _hiddenUnlocked.value = true
        true
    }

    fun verifyPin(pin: String): Boolean {
        synchronized(PIN_LOCK) {
            val stored = prefs.getString(KEY_PIN_HASH, null) ?: return !_pinIsSet.value
            val nowElapsed = SystemClock.elapsedRealtime()
            val reconciled = PinLockPolicy.reconcile(
                PinLockState(
                    failures = prefs.getInt(KEY_PIN_FAILURES, 0).coerceAtLeast(0),
                    lockUntilElapsedMillis = prefs.getLong(KEY_PIN_LOCK_UNTIL_ELAPSED, 0L),
                    lockDurationMillis = prefs.getLong(KEY_PIN_LOCK_DURATION, 0L),
                    lastElapsedMillis = prefs.getLong(KEY_PIN_LAST_ELAPSED, -1L),
                ),
                nowElapsed,
            )
            if (!persistPinState(reconciled)) return false
            if (PinLockPolicy.isLocked(reconciled, nowElapsed)) return false
            val candidate = runCatching {
                if (stored.startsWith(PIN_HMAC_PREFIX)) {
                    PIN_HMAC_PREFIX + hashPin(pin)
                } else {
                    legacyHashPin(pin)
                }
            }.getOrElse { return false }
            val matches = java.security.MessageDigest.isEqual(
                stored.toByteArray(Charsets.US_ASCII),
                candidate.toByteArray(Charsets.US_ASCII),
            )
            if (matches) {
                val update = prefs.edit()
                    .remove(KEY_PIN_FAILURES)
                    .remove(KEY_PIN_LOCK_UNTIL)
                    .remove(KEY_PIN_LOCK_UNTIL_ELAPSED)
                    .remove(KEY_PIN_LOCK_DURATION)
                    .putLong(KEY_PIN_LAST_ELAPSED, nowElapsed)
                if (!stored.startsWith(PIN_HMAC_PREFIX)) {
                    update.putString(KEY_PIN_HASH, PIN_HMAC_PREFIX + hashPin(pin))
                }
                return update.commit()
            }
            val failed = PinLockPolicy.registerFailure(reconciled, nowElapsed)
            if (!persistPinState(failed)) return false
            return false
        }
    }

    private fun persistPinState(state: PinLockState): Boolean =
        prefs.edit()
            .putInt(KEY_PIN_FAILURES, state.failures)
            .putLong(KEY_PIN_LOCK_UNTIL_ELAPSED, state.lockUntilElapsedMillis)
            .putLong(KEY_PIN_LOCK_DURATION, state.lockDurationMillis)
            .putLong(KEY_PIN_LAST_ELAPSED, state.lastElapsedMillis)
            // Do not leave the old wall-clock deadline available to older code after migration.
            .remove(KEY_PIN_LOCK_UNTIL)
            .commit()

    fun setHiddenCategories(keys: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN_CATS, keys).apply()
        _hiddenCategories.value = keys.toSet()
    }

    fun setHiddenUnlocked(unlocked: Boolean) {
        _hiddenUnlocked.value = unlocked
    }

    private fun hashPin(pin: String): String = secretStore.hmac(PIN_HMAC_KEY, pin)
        .joinToString("") { "%02x".format(it) }

    private fun legacyHashPin(pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("opentv-pin::$pin".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setInterfaceStyle(style: InterfaceStyle) {
        prefs.edit().putString(KEY_INTERFACE_STYLE, style.name).apply()
        _interfaceStyle.value = style
    }

    fun setPersistentClock(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSISTENT_CLOCK, enabled).apply()
        _persistentClock.value = enabled
    }

    fun setChannelLayout(layout: ChannelLayout) {
        prefs.edit().putString(KEY_CHANNEL_LAYOUT, layout.name).apply()
        _channelLayout.value = layout
    }

    fun setGuideDensity(density: GuideDensity) {
        prefs.edit().putString(KEY_GUIDE_DENSITY, density.name).apply()
        _guideDensity.value = density
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUBTITLES, enabled).apply()
        _subtitlesEnabled.value = enabled
    }

    fun setAutoPortugueseSubtitles(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PT_SUBTITLES, enabled).apply()
        _autoPortugueseSubtitles.value = enabled
    }

    fun setGuidePreviewVideo(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW_VIDEO, enabled).apply()
        _guidePreviewVideo.value = enabled
    }

    fun setGuidePreviewSound(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW_SOUND, enabled).apply()
        _guidePreviewSound.value = enabled
    }

    /** Whether launching the app jumps straight back to the last channel you watched. */
    private val _resumeLastChannel = MutableStateFlow(prefs.getBoolean(KEY_RESUME_LAST, false))
    val resumeLastChannel: StateFlow<Boolean> = _resumeLastChannel.asStateFlow()

    fun setResumeLastChannel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESUME_LAST, enabled).apply()
        _resumeLastChannel.value = enabled
    }

    // ---- Catalogue and guide refresh ---------------------------------------------------------

    /** Whether channels and guides are refreshed automatically in the background. */
    private val _catalogAutoRefresh = MutableStateFlow(prefs.getBoolean(KEY_CATALOG_AUTO_REFRESH, true))
    val catalogAutoRefresh: StateFlow<Boolean> = _catalogAutoRefresh.asStateFlow()

    /** Period between background refreshes; WorkManager applies its minimum platform interval. */
    private val _catalogRefreshIntervalHours = MutableStateFlow(
        prefs.getInt(KEY_CATALOG_REFRESH_HOURS, 1).coerceIn(1, 24),
    )
    val catalogRefreshIntervalHours: StateFlow<Int> = _catalogRefreshIntervalHours.asStateFlow()

    /** When enabled, the user is asked at startup whether to reset channels and guides now. */
    private val _catalogAskOnStart = MutableStateFlow(prefs.getBoolean(KEY_CATALOG_ASK_ON_START, true))
    val catalogAskOnStart: StateFlow<Boolean> = _catalogAskOnStart.asStateFlow()

    fun setCatalogAutoRefresh(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CATALOG_AUTO_REFRESH, enabled).apply()
        _catalogAutoRefresh.value = enabled
    }

    fun setCatalogRefreshIntervalHours(hours: Int) {
        val h = hours.coerceIn(1, 24)
        prefs.edit().putInt(KEY_CATALOG_REFRESH_HOURS, h).apply()
        _catalogRefreshIntervalHours.value = h
    }

    fun setCatalogAskOnStart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CATALOG_ASK_ON_START, enabled).apply()
        _catalogAskOnStart.value = enabled
    }

    // ---- Recording behaviour -----------------------------------------------------------------

    /** Minutes to start a scheduled recording early, so a late start isn't clipped. */
    private val _recordPadStartMinutes = MutableStateFlow(prefs.getInt(KEY_PAD_START, 1))
    val recordPadStartMinutes: StateFlow<Int> = _recordPadStartMinutes.asStateFlow()

    /** Minutes to keep recording past the listed end, so an overrun (sport/news) isn't cut off. */
    private val _recordPadEndMinutes = MutableStateFlow(prefs.getInt(KEY_PAD_END, 5))
    val recordPadEndMinutes: StateFlow<Int> = _recordPadEndMinutes.asStateFlow()

    /** Days of future EPG data to retain and scan for upcoming series recordings. */
    private val _recordScheduleLookaheadDays = MutableStateFlow(
        prefs.getInt(KEY_REC_SCHEDULE_LOOKAHEAD_DAYS, DEFAULT_REC_SCHEDULE_LOOKAHEAD_DAYS)
            .coerceIn(MIN_REC_SCHEDULE_LOOKAHEAD_DAYS, MAX_REC_SCHEDULE_LOOKAHEAD_DAYS),
    )
    val recordScheduleLookaheadDays: StateFlow<Int> = _recordScheduleLookaheadDays.asStateFlow()

    /**
     * How long a manual "record now" runs when the channel has no guide entry to bound it.
     * Default 60 minutes, matching the common "record an hour of this" request; 0 means keep
     * recording until the user stops it (the historical behaviour).
     */
    private val _manualRecordMinutes = MutableStateFlow(prefs.getInt(KEY_MANUAL_REC_MIN, 60))
    val manualRecordMinutes: StateFlow<Int> = _manualRecordMinutes.asStateFlow()

    fun setManualRecordMinutes(minutes: Int) {
        val m = minutes.coerceIn(0, 240)
        prefs.edit().putInt(KEY_MANUAL_REC_MIN, m).apply()
        _manualRecordMinutes.value = m
    }

    fun setRecordPadding(startMinutes: Int, endMinutes: Int) {
        val s = startMinutes.coerceIn(0, 30)
        val e = endMinutes.coerceIn(0, 60)
        prefs.edit().putInt(KEY_PAD_START, s).putInt(KEY_PAD_END, e).apply()
        _recordPadStartMinutes.value = s
        _recordPadEndMinutes.value = e
    }

    fun setRecordScheduleLookaheadDays(days: Int) {
        val value = days.coerceIn(MIN_REC_SCHEDULE_LOOKAHEAD_DAYS, MAX_REC_SCHEDULE_LOOKAHEAD_DAYS)
        prefs.edit().putInt(KEY_REC_SCHEDULE_LOOKAHEAD_DAYS, value).apply()
        _recordScheduleLookaheadDays.value = value
    }

    /**
     * When a scheduled recording starts, switch the box to it (playing from the growing file, so no
     * extra provider connection). Essential on a single-connection provider, where recording one
     * channel while another plays gets one of them cut. Default on.
     */
    private val _recordAutoSwitch = MutableStateFlow(prefs.getBoolean(KEY_REC_AUTOSWITCH, true))
    val recordAutoSwitch: StateFlow<Boolean> = _recordAutoSwitch.asStateFlow()

    fun setRecordAutoSwitch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REC_AUTOSWITCH, enabled).apply()
        _recordAutoSwitch.value = enabled
    }

    /**
     * Pause & rewind live TV via a rolling on-disk buffer of the current channel. Experimental and
     * heavier on weak boxes, so it's opt-in (off by default); when off, playback is unchanged.
     */
    private val _livePauseEnabled = MutableStateFlow(prefs.getBoolean(KEY_LIVE_PAUSE, false))
    val livePauseEnabled: StateFlow<Boolean> = _livePauseEnabled.asStateFlow()

    fun setLivePauseEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_PAUSE, enabled).apply()
        _livePauseEnabled.value = enabled
    }

    // ---- Content types -----------------------------------------------------------------------

    /**
     * Which content types the user wants synced and shown. Turning one off skips fetching that
     * type's catalogue on the next sync (the speed-up) and hides its tab; already-synced rows are
     * left in place, so turning it back on and refreshing brings everything straight back.
     */
    private val _liveEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTENT_LIVE, true))
    val liveEnabled: StateFlow<Boolean> = _liveEnabled.asStateFlow()

    private val _moviesEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTENT_MOVIES, true))
    val moviesEnabled: StateFlow<Boolean> = _moviesEnabled.asStateFlow()

    private val _seriesEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTENT_SERIES, true))
    val seriesEnabled: StateFlow<Boolean> = _seriesEnabled.asStateFlow()

    fun setLiveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTENT_LIVE, enabled).apply()
        _liveEnabled.value = enabled
    }

    fun setMoviesEnabled(enabled: Boolean) {
        // Also reset the VOD freshness stamp so the next Movies/Shows open re-syncs immediately
        // rather than waiting out the cache TTL — turning a content type on should show it now.
        prefs.edit()
            .putBoolean(KEY_CONTENT_MOVIES, enabled)
            .putLong(KEY_VOD_SYNCED_AT, 0L)
            .apply()
        _moviesEnabled.value = enabled
    }

    fun setSeriesEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_CONTENT_SERIES, enabled)
            .putLong(KEY_VOD_SYNCED_AT, 0L)
            .apply()
        _seriesEnabled.value = enabled
    }

    /**
     * UI language override. A missing value defaults to Brazilian Portuguese; blank means the user
     * explicitly chose to follow the device. A legacy generic "pt" choice maps to Portugal.
     * Applied at [android.content.ContextWrapper.attachBaseContext] so the whole app — including
     * notifications built from the app context — picks it up.
     */
    private val _languageTag = MutableStateFlow(
        LanguageTagPolicy.fromStoredValue(prefs.getString(KEY_LANGUAGE, null)),
    )
    val languageTag: StateFlow<String> = _languageTag.asStateFlow()

    fun setLanguageTag(tag: String) {
        val supportedTag = LanguageTagPolicy.fromStoredValue(tag)
        prefs.edit().putString(KEY_LANGUAGE, supportedTag).apply()
        _languageTag.value = supportedTag
    }

    /** The last channel played, for boot-to-last-channel. Not a flow — only read once at launch. */
    var lastChannelId: Long
        get() = prefs.getLong(KEY_LAST_CHANNEL, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_CHANNEL, value).apply() }

    /**
     * Video scaling in the player, as an [androidx.media3.ui.AspectRatioFrameLayout] RESIZE_MODE_*
     * constant (0 = Fit). Persisted so the choice survives leaving the player, which testers asked
     * for — picking Fill every single time you open a channel gets old fast.
     */
    private val _playerResizeMode = MutableStateFlow(prefs.getInt(KEY_RESIZE_MODE, 0))
    val playerResizeMode: StateFlow<Int> = _playerResizeMode.asStateFlow()

    fun setPlayerResizeMode(mode: Int) {
        prefs.edit().putInt(KEY_RESIZE_MODE, mode).apply()
        _playerResizeMode.value = mode
    }

    private fun readThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)

    private fun readChannelLayout(): ChannelLayout =
        runCatching { ChannelLayout.valueOf(prefs.getString(KEY_CHANNEL_LAYOUT, null) ?: "") }
            .getOrDefault(ChannelLayout.GRID)

    private fun readInterfaceStyle(): InterfaceStyle =
        runCatching { InterfaceStyle.valueOf(prefs.getString(KEY_INTERFACE_STYLE, null) ?: "") }
            .getOrDefault(InterfaceStyle.CURRENT)

    private fun readGuideDensity(): GuideDensity =
        runCatching { GuideDensity.valueOf(prefs.getString(KEY_GUIDE_DENSITY, null) ?: "") }
            .getOrDefault(GuideDensity.NORMAL)

    // ---- Recording ---------------------------------------------------------------------------

    /**
     * Where recordings are written: this box's internal storage, a NAS over SMB, or a plugged-in
     * USB / external drive addressed through the Storage Access Framework (a granted tree URI —
     * the only way to write removable storage on modern Android without a raw filesystem path).
     */
    enum class RecordingTarget { INTERNAL, SMB, USB, FTP }

    private val _recordingTarget = MutableStateFlow(readRecordingTarget())
    val recordingTarget: StateFlow<RecordingTarget> = _recordingTarget.asStateFlow()

    /**
     * What the finished file is: the raw live MPEG-TS stream exactly as captured ([TS]), or an MP4
     * remuxed from it after capture ([MP4]). Capture always starts as TS — live endpoints deliver
     * TS and an MP4 needs its index rewritten at the end — so MP4 costs a short conversion pass.
     */
    enum class RecordingContainer { TS, MP4 }

    private val _recordingContainer = MutableStateFlow(readRecordingContainer())
    val recordingContainer: StateFlow<RecordingContainer> = _recordingContainer.asStateFlow()

    /**
     * The SAF tree URI the user granted for USB recordings (a `content://` document-tree URI), or
     * null if none has been picked. Persisted as a string; a matching persistable permission is
     * taken when it is chosen, so the grant survives restarts.
     */
    private val _usbTreeUri = MutableStateFlow(prefs.getString(KEY_USB_TREE, null))
    val usbTreeUri: StateFlow<String?> = _usbTreeUri.asStateFlow()

    /** A friendly name for the chosen USB folder (e.g. the DocumentFile's name), for display. */
    private val _usbFolderLabel = MutableStateFlow(prefs.getString(KEY_USB_LABEL, null))
    val usbFolderLabel: StateFlow<String?> = _usbFolderLabel.asStateFlow()

    /** Save (or clear, with null) the granted USB tree URI and its display label together. */
    fun setUsbTree(treeUri: String?, label: String?) {
        prefs.edit()
            .putString(KEY_USB_TREE, treeUri)
            .putString(KEY_USB_LABEL, label)
            .apply()
        _usbTreeUri.value = treeUri
        _usbFolderLabel.value = label
    }

    /** SMB / NAS connection. Stored on-device only, exactly like provider credentials. */
    private val _smbHost = MutableStateFlow(prefs.getString(KEY_SMB_HOST, "").orEmpty())
    val smbHost: StateFlow<String> = _smbHost.asStateFlow()

    private val _smbShare = MutableStateFlow(prefs.getString(KEY_SMB_SHARE, "").orEmpty())
    val smbShare: StateFlow<String> = _smbShare.asStateFlow()

    /** Sub-folder within the share, e.g. `Recordings`. Blank = share root. */
    private val _smbFolder = MutableStateFlow(prefs.getString(KEY_SMB_FOLDER, "OpenTV").orEmpty())
    val smbFolder: StateFlow<String> = _smbFolder.asStateFlow()

    private val _smbUser = MutableStateFlow(readSecret(KEY_SMB_USER))
    val smbUser: StateFlow<String> = _smbUser.asStateFlow()

    private val _smbPassword = MutableStateFlow(readSecret(KEY_SMB_PASS))
    val smbPassword: StateFlow<String> = _smbPassword.asStateFlow()

    /** FTP is a deliberately cleartext exception, restricted by FtpClient to a private IPv4 LAN. */
    private val _ftpHost = MutableStateFlow(prefs.getString(KEY_FTP_HOST, "").orEmpty())
    val ftpHost: StateFlow<String> = _ftpHost.asStateFlow()
    private val _ftpPort = MutableStateFlow(prefs.getInt(KEY_FTP_PORT, 21).coerceIn(1, 65_535))
    val ftpPort: StateFlow<Int> = _ftpPort.asStateFlow()
    private val _ftpFolder = MutableStateFlow(prefs.getString(KEY_FTP_FOLDER, "FullTV").orEmpty())
    val ftpFolder: StateFlow<String> = _ftpFolder.asStateFlow()
    private val _ftpUser = MutableStateFlow(readSecret(KEY_FTP_USER))
    val ftpUser: StateFlow<String> = _ftpUser.asStateFlow()
    private val _ftpPassword = MutableStateFlow(readSecret(KEY_FTP_PASS))
    val ftpPassword: StateFlow<String> = _ftpPassword.asStateFlow()
    private val _ftpPlaintextAcknowledged =
        MutableStateFlow(prefs.getBoolean(KEY_FTP_PLAINTEXT_ACK, false))
    val ftpPlaintextAcknowledged: StateFlow<Boolean> = _ftpPlaintextAcknowledged.asStateFlow()

    fun setRecordingTarget(target: RecordingTarget) {
        prefs.edit().putString(KEY_REC_TARGET, target.name).apply()
        _recordingTarget.value = target
    }

    fun setRecordingContainer(container: RecordingContainer) {
        prefs.edit().putString(KEY_REC_CONTAINER, container.name).apply()
        _recordingContainer.value = container
    }

    private fun readRecordingContainer(): RecordingContainer =
        runCatching { RecordingContainer.valueOf(prefs.getString(KEY_REC_CONTAINER, null) ?: "") }
            .getOrDefault(RecordingContainer.TS)

    fun setSmbConfig(host: String, share: String, folder: String, user: String, password: String) {
        writeSecret(KEY_SMB_USER, user)
        writeSecret(KEY_SMB_PASS, password)
        prefs.edit()
            .putString(KEY_SMB_HOST, host.trim())
            .putString(KEY_SMB_SHARE, share.trim())
            .putString(KEY_SMB_FOLDER, folder.trim())
            .remove(KEY_SMB_USER)
            .remove(KEY_SMB_PASS)
            .apply()
        _smbHost.value = host.trim()
        _smbShare.value = share.trim()
        _smbFolder.value = folder.trim()
        _smbUser.value = user
        _smbPassword.value = password
    }

    /** Returns false if the encrypted credential write or durable preferences commit fails. */
    fun setFtpConfig(
        host: String,
        port: Int,
        folder: String,
        user: String,
        password: String,
        plaintextAcknowledged: Boolean,
    ): Boolean {
        if (port !in 1..65_535) return false
        val previousUser = _ftpUser.value
        val previousPassword = _ftpPassword.value
        val previousHost = _ftpHost.value
        val previousPort = _ftpPort.value
        val previousFolder = _ftpFolder.value
        val previousAcknowledgement = _ftpPlaintextAcknowledged.value
        var userWritten = false
        var passwordWritten = false
        var preferencesAttempted = false
        val success = runCatching {
            userWritten = true
            writeSecret(KEY_FTP_USER, user)
            passwordWritten = true
            writeSecret(KEY_FTP_PASS, password)
            val cleanHost = host.trim()
            val cleanFolder = folder.trim()
            preferencesAttempted = true
            check(
                prefs.edit()
                    .putString(KEY_FTP_HOST, cleanHost)
                    .putInt(KEY_FTP_PORT, port)
                    .putString(KEY_FTP_FOLDER, cleanFolder)
                    .putBoolean(KEY_FTP_PLAINTEXT_ACK, plaintextAcknowledged)
                    .remove(KEY_FTP_USER)
                    .remove(KEY_FTP_PASS)
                    .commit(),
            ) { "FTP settings could not be saved." }
            _ftpHost.value = cleanHost
            _ftpPort.value = port
            _ftpFolder.value = cleanFolder
            _ftpUser.value = user
            _ftpPassword.value = password
            _ftpPlaintextAcknowledged.value = plaintextAcknowledged
        }.isSuccess
        if (!success) {
            // If any encrypted write or the settings commit failed, restore the prior config.
            if (preferencesAttempted) runCatching {
                prefs.edit()
                    .putString(KEY_FTP_HOST, previousHost)
                    .putInt(KEY_FTP_PORT, previousPort)
                    .putString(KEY_FTP_FOLDER, previousFolder)
                    .putBoolean(KEY_FTP_PLAINTEXT_ACK, previousAcknowledgement)
                    .remove(KEY_FTP_USER)
                    .remove(KEY_FTP_PASS)
                    .commit()
            }
            if (passwordWritten) runCatching { writeSecret(KEY_FTP_PASS, previousPassword) }
            if (userWritten) runCatching { writeSecret(KEY_FTP_USER, previousUser) }
        }
        return success
    }

    private fun readRecordingTarget(): RecordingTarget =
        runCatching { RecordingTarget.valueOf(prefs.getString(KEY_REC_TARGET, null) ?: "") }
            .getOrDefault(RecordingTarget.INTERNAL)

    // ---- NAS ("cloud") sync ------------------------------------------------------------------

    /**
     * A stable, random id for this install. It names this device's bundle file in the NAS sync
     * folder, so every device writes its own file and reads the others'. Generated once, on first
     * read, then persisted — distinct per device without needing any hardware identifier.
     */
    val syncDeviceId: String
        get() = prefs.getString(KEY_SYNC_DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString()
            .also { prefs.edit().putString(KEY_SYNC_DEVICE_ID, it).apply() }

    /** Whether to run a NAS sync automatically each time the app is opened. Off by default. */
    private val _nasAutoSync = MutableStateFlow(prefs.getBoolean(KEY_NAS_AUTO_SYNC, false))
    val nasAutoSync: StateFlow<Boolean> = _nasAutoSync.asStateFlow()

    fun setNasAutoSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NAS_AUTO_SYNC, enabled).apply()
        _nasAutoSync.value = enabled
    }

    // ---- Catalogue freshness & TMDB ----------------------------------------------------------

    /**
     * When the VOD (movies + series) catalogue was last fetched from the provider, in epoch
     * millis; 0 = never. A provider's 40k-title VOD list is expensive to re-download and
     * re-write, so [app.opentv.ui.VodViewModel.ensureVodLoaded] uses this to skip the sync on a
     * warm launch and show the already-stored rows instantly — the fetch only runs on first load
     * or once this goes stale. Not a flow: it is read once when Movies/Shows is first opened.
     */
    var vodSyncedAtMillis: Long
        get() = prefs.getLong(KEY_VOD_SYNCED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_VOD_SYNCED_AT, value).apply() }

    /**
     * The user's own TMDB API key (v3 auth), stored on-device only exactly like provider
     * credentials, used to fill in artwork/metadata a provider left blank. Blank = the TMDB
     * fallback is off. Each user brings their own key, so no single key carries everyone's traffic.
     */
    private val _tmdbApiKey = MutableStateFlow(readSecret(KEY_TMDB_KEY))
    val tmdbApiKey: StateFlow<String> = _tmdbApiKey.asStateFlow()

    fun setTmdbApiKey(key: String) {
        val trimmed = key.trim()
        writeSecret(KEY_TMDB_KEY, trimmed)
        _tmdbApiKey.value = trimmed
    }

    // ---- Stremio add-ons ---------------------------------------------------------------------

    /**
     * The user's Stremio add-ons, stored on-device as JSON. OpenTV ships with none; each entry is a
     * manifest URL the user pasted (a debrid key, if any, is baked into that URL on the add-on's own
     * site — never entered here). Empty = the whole feature stays inert.
     */
    private val _stremioAddons = MutableStateFlow(readStremioAddons())
    val stremioAddons: StateFlow<List<StremioAddon>> = _stremioAddons.asStateFlow()

    fun addStremioAddon(addon: StremioAddon) {
        val deduped = _stremioAddons.value.filterNot { it.manifestUrl.equals(addon.manifestUrl, ignoreCase = true) }
        persistStremioAddons(deduped + addon)
    }

    fun removeStremioAddon(manifestUrl: String) {
        persistStremioAddons(_stremioAddons.value.filterNot { it.manifestUrl == manifestUrl })
    }

    private fun persistStremioAddons(list: List<StremioAddon>) {
        writeSecret(KEY_STREMIO_ADDONS, addonJson.encodeToString(list))
        _stremioAddons.value = list
    }

    private fun readStremioAddons(): List<StremioAddon> =
        runCatching {
            readSecret(KEY_STREMIO_ADDONS).takeIf { it.isNotEmpty() }
                ?.let { addonJson.decodeFromString<List<StremioAddon>>(it) }
        }.getOrNull() ?: emptyList()

    /**
     * Reads an encrypted secret, migrating a legacy preference only after a successful write,
     * decrypt-and-compare verification, and durable removal of the plaintext source. A Keystore or
     * decrypt failure never exposes the legacy value to runtime callers.
     */
    private fun readSecret(key: String): String {
        return when (val encrypted = secretStore.get(key)) {
            is EncryptedSecretStore.ReadResult.Present -> {
                if (prefs.contains(key)) prefs.edit().remove(key).commit()
                encrypted.value
            }
            EncryptedSecretStore.ReadResult.Absent -> {
                val legacy = prefs.getString(key, null) ?: return ""
                migrateSecret(key, legacy)
            }
            EncryptedSecretStore.ReadResult.DecryptError -> ""
            EncryptedSecretStore.ReadResult.KeystoreUnavailable -> ""
        }
    }

    private fun migrateSecret(key: String, legacy: String): String = runCatching {
        secretStore.put(key, legacy)
        val verified = secretStore.get(key)
        check(verified is EncryptedSecretStore.ReadResult.Present && verified.value == legacy) {
            "Encrypted secret could not be verified after migration."
        }
        check(prefs.edit().remove(key).commit()) { "Could not remove plaintext secret." }
        verified.value
    }.getOrDefault("")

    private fun writeSecret(key: String, value: String) {
        secretStore.put(key, value)
        prefs.edit().remove(key).apply()
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_CHANNEL_LAYOUT = "channel_layout"
        private const val KEY_INTERFACE_STYLE = "interface_style"
        private const val KEY_PERSISTENT_CLOCK = "persistent_clock"
        private const val KEY_GUIDE_DENSITY = "guide_density"
        private const val KEY_SUBTITLES = "subtitles_enabled"
        private const val KEY_AUTO_PT_SUBTITLES = "auto_pt_subtitles"
        private const val KEY_PREVIEW_VIDEO = "guide_preview_video"
        private const val KEY_PREVIEW_SOUND = "guide_preview_sound"
        private const val KEY_PIN_HASH = "parental_pin_hash"
        private const val KEY_PIN_FAILURES = "parental_pin_failed_attempts"
        /** Legacy wall-clock key is removed and never used as a runtime deadline. */
        private const val KEY_PIN_LOCK_UNTIL = "parental_pin_lock_until"
        private const val KEY_PIN_LOCK_UNTIL_ELAPSED = "parental_pin_lock_until_elapsed"
        private const val KEY_PIN_LOCK_DURATION = "parental_pin_lock_duration"
        private const val KEY_PIN_LAST_ELAPSED = "parental_pin_last_elapsed"
        private const val KEY_HIDDEN_CATS = "hidden_categories"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
        private const val KEY_RESUME_LAST = "resume_last_channel"
        private const val KEY_CATALOG_AUTO_REFRESH = "catalog_auto_refresh"
        private const val KEY_CATALOG_REFRESH_HOURS = "catalog_refresh_hours"
        private const val KEY_CATALOG_ASK_ON_START = "catalog_ask_on_start"
        private const val KEY_CONTENT_LIVE = "content_live"
        private const val KEY_CONTENT_MOVIES = "content_movies"
        private const val KEY_CONTENT_SERIES = "content_series"
        private const val KEY_LAST_CHANNEL = "last_channel_id"
        private const val KEY_RESIZE_MODE = "player_resize_mode"
        private const val KEY_LANGUAGE = "language_tag"

        /** Reads the saved language before settings initialize; a missing choice defaults to pt-BR. */
        fun savedLanguageTag(context: Context): String =
            LanguageTagPolicy.fromStoredValue(
                context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
                    .getString(KEY_LANGUAGE, null),
            )
        private const val KEY_REC_TARGET = "recording_target"
        private const val KEY_REC_CONTAINER = "recording_container"
        private const val KEY_SMB_HOST = "smb_host"
        private const val KEY_SMB_SHARE = "smb_share"
        private const val KEY_SMB_FOLDER = "smb_folder"
        private const val KEY_SMB_USER = "smb_user"
        private const val KEY_SMB_PASS = "smb_password"
        private const val KEY_FTP_HOST = "ftp_host"
        private const val KEY_FTP_PORT = "ftp_port"
        private const val KEY_FTP_FOLDER = "ftp_folder"
        private const val KEY_FTP_USER = "ftp_username"
        private const val KEY_FTP_PASS = "ftp_password"
        private const val KEY_FTP_PLAINTEXT_ACK = "ftp_plaintext_acknowledged"
        private const val KEY_USB_TREE = "usb_tree_uri"
        private const val KEY_USB_LABEL = "usb_folder_label"
        private const val KEY_SYNC_DEVICE_ID = "sync_device_id"
        private const val KEY_NAS_AUTO_SYNC = "nas_auto_sync"
        private const val KEY_VOD_SYNCED_AT = "vod_synced_at"
        private const val KEY_TMDB_KEY = "tmdb_api_key"
        private const val KEY_STREMIO_ADDONS = "stremio_addons"
        private const val PIN_LENGTH = 4
        private const val PIN_HMAC_KEY = "parental_pin_v1"
        private const val PIN_HMAC_PREFIX = "hmac-v1:"
        private val PIN_LOCK = Any()
        private const val KEY_MANUAL_REC_MIN = "rec_manual_minutes"
        private const val KEY_PAD_START = "rec_pad_start_min"
        private const val KEY_PAD_END = "rec_pad_end_min"
        private const val KEY_REC_SCHEDULE_LOOKAHEAD_DAYS = "rec_schedule_lookahead_days"
        private const val KEY_REC_AUTOSWITCH = "rec_auto_switch"
        private const val KEY_LIVE_PAUSE = "live_pause_enabled"
        private const val DEFAULT_REC_SCHEDULE_LOOKAHEAD_DAYS = 2
        private const val MIN_REC_SCHEDULE_LOOKAHEAD_DAYS = 2
        private const val MAX_REC_SCHEDULE_LOOKAHEAD_DAYS = 14

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
    }
}
