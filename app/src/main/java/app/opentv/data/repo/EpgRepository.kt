/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.EpgChannelAliasDao
import app.opentv.data.db.EpgFeedDao
import app.opentv.data.db.ProgrammeDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.parser.BoundedInputStream
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.XmltvParser
import app.opentv.data.remote.XtreamApi
import app.opentv.data.remote.HttpsUrlPolicy
import app.opentv.data.remote.NetworkDestinationPolicy
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Owns the electronic programme guide, end to end: feeds in, matched channels out.
 *
 * ## The feed model
 *
 * Guide data can come from three places at once — the provider's own XMLTV (usually thin or
 * empty), the curated free sources shipped with the app, and URLs the user adds. Each is an
 * [EpgFeed] row; every enabled feed is downloaded and merged into one guide. After a sync,
 * [EpgMatcher] joins provider channels to guide channels by normalised name, which is what
 * makes a free national guide light up channels named `UK| BBC ONE FHD`.
 *
 * ## The failure this class exists to prevent
 *
 * The standard way to refresh an EPG is: delete everything, download the new XMLTV, insert
 * it. It is simple and it is why so many IPTV players lose their guide — one stalled
 * download and the user is left with nothing, plus advice to reinstall.
 *
 * OpenTV never deletes before it has the replacement:
 *
 * - Programmes and aliases are **staged under a private negative feed id** as they stream out
 *   of the parser. Public guide queries ignore staging rows. Only a fully parsed snapshot replaces
 *   the old feed, in one database transaction; failures and cancellation retain the old guide.
 * - Old programmes are pruned **by age**, only after at least one feed succeeds.
 * - A failed feed leaves that feed's previous data intact and records the reason on the
 *   feed row, where the settings screen shows it — no silent failure.
 */
class EpgRepository(
    private val database: OpenTvDatabase,
    private val programmeDao: ProgrammeDao,
    private val feedDao: EpgFeedDao,
    private val aliasDao: EpgChannelAliasDao,
    private val channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val api: XtreamApi,
    private val http: OkHttpClient,
    private val settings: AppSettings,
) {
    private val syncMutex = Mutex()

    /** Future guide window used both while parsing feeds and while pruning old database rows. */
    val retentionFutureMillis: Long
        get() = TimeUnit.DAYS.toMillis(settings.recordScheduleLookaheadDays.value.toLong())

    data class SyncSummary(
        val feedsSucceeded: Int,
        val feedsFailed: Int,
        val programmesWritten: Int,
        val channelsMatched: Int,
        val channelsTotal: Int,
    )

    // ---- Reads -----------------------------------------------------------------------------

    fun observeFeeds(): Flow<List<EpgFeed>> = feedDao.observeAll()

    fun observeWindow(fromUtcMillis: Long, toUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeWindow(fromUtcMillis, toUtcMillis)

    fun observeNow(nowUtcMillis: Long): Flow<List<Programme>> =
        programmeDao.observeNow(nowUtcMillis)

    suspend fun upcoming(epgChannelId: String, nowUtcMillis: Long, limit: Int = 12): List<Programme> =
        programmeDao.upcoming(epgChannelId, nowUtcMillis, limit)

    // ---- Feed management -------------------------------------------------------------------

    suspend fun addCustomFeed(name: String, url: String) {
        val trimmed = HttpsUrlPolicy.withDefaultHttps(url)
        if (trimmed.isEmpty() || feedDao.byUrl(trimmed) != null) return
        HttpsUrlPolicy.requireHttps(trimmed)
        feedDao.insert(
            EpgFeed(name = name.trim().ifBlank { trimmed }, url = trimmed, enabled = true),
        )
    }

    /**
     * Registers every guide URL a playlist declared (`url-tvg` / `x-tvg-url`) as its own feed.
     *
     * Real lists advertise one guide per country — often dozens — and repeating the attribute is
     * the only signal they give. Every additional unique guide URL is added and enabled automatically,
     * as requested; large multi-country playlists can therefore use substantial network and storage.
     * [skipUrl] is the one already adopted as the provider guide, so it is not duplicated.
     * On every playlist refresh, feeds previously discovered by that same playlist but no longer
     * declared are removed together with their aliases/programmes. Manual and built-in feeds are
     * never touched.
     */
    suspend fun importDiscoveredFeeds(
        source: Source,
        urls: List<String>,
        skipUrl: String? = null,
    ) {
        // The first URL remains the source's provider guide; all other unique declared URLs are
        // imported and enabled automatically. Existing feed rows retain their explicit user choice.
        val skip = skipUrl?.trim()
        val discoveredUrls = urls.map { it.trim() }
            .filter { it.isNotEmpty() && it != skip }
            .filter { it.startsWith("https://", true) }
            .toSet()
        val owned = feedDao.discoveredForPlaylist(source.id)
        owned.filter { it.url !in discoveredUrls }.forEach { removeFeed(it) }
        for (raw in discoveredUrls) {
            val url = raw.trim()
            val existing = feedDao.byUrl(url)
            if (existing != null) {
                if (existing.playlistSourceId == source.id) {
                    // Keep the user's enabled/disabled choice, but refresh the display name.
                    feedDao.update(existing.copy(name = feedName(source, url)))
                }
                continue
            }
            feedDao.insert(
                EpgFeed(
                    name = feedName(source, url),
                    url = url,
                    builtIn = false,
                    enabled = true,
                    playlistSourceId = source.id,
                ),
            )
        }
    }

    /** A short, recognisable label: the provider plus the guide's host. */
    private fun feedName(source: Source, url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
            ?: url.substringAfter("://").substringBefore('/').substringBefore('?')
        return if (host.isBlank()) source.name else "${source.name} — $host"
    }

    suspend fun setFeedEnabled(id: Long, enabled: Boolean) = feedDao.setEnabled(id, enabled)

    /** Explicit bulk action: pause playlist-discovered feeds and remove their stored guide rows. */
    suspend fun disableDiscoveredFeedsAndClearData(): Int {
        val discovered = feedDao.all().filter { it.playlistSourceId != null }
        discovered.forEach { feed ->
            if (feed.enabled) feedDao.setEnabled(feed.id, false)
            programmeDao.deleteForFeed(feed.id)
            aliasDao.deleteForFeed(feed.id)
        }
        return discovered.size
    }

    suspend fun removeFeed(feed: EpgFeed) {
        // A removed feed takes its guide data with it — orphaned programmes would otherwise
        // keep matching channels forever with content that never refreshes.
        programmeDao.deleteForFeed(feed.id)
        aliasDao.deleteForFeed(feed.id)
        feedDao.delete(feed.id)
    }

    /**
     * Makes sure the standing feed rows exist: one per provider source, plus the curated
     * built-in list. Insert-if-absent, so user toggles survive every call.
     */
    suspend fun ensureFeeds() {
        for (source in sourceDao.enabled()) {
            if (feedDao.forProvider(source.id) == null) {
                feedDao.insert(
                    EpgFeed(
                        name = "${source.name} (provider guide)",
                        providerSourceId = source.id,
                        enabled = true,
                    ),
                )
            }
        }
        for ((name, url) in BUILT_IN_FEEDS) {
            if (feedDao.byUrl(url) == null) {
                // Off by default: shipping a switched-on 20 MB download for a country the
                // user may not live in would be rude. The EPG settings screen makes
                // enabling one a single click.
                feedDao.insert(EpgFeed(name = name, url = url, builtIn = true, enabled = false))
            }
        }
    }

    // ---- Sync ------------------------------------------------------------------------------

    /** Downloads every enabled feed, merges, prunes, and re-runs the matcher. */
    suspend fun syncAll(nowUtcMillis: Long, force: Boolean = false): SyncSummary =
        withContext(Dispatchers.IO) {
          syncMutex.withLock {
            // A process death can leave private staging rows behind. They are never displayed;
            // clear them before starting the next serialized sync.
            database.withTransaction {
                programmeDao.deleteStaging()
                aliasDao.deleteStaging()
            }
            val futureCutoffMillis = nowUtcMillis + retentionFutureMillis
            ensureFeeds()
            maybeAutoEnableRegionalFeed()

            var succeeded = 0
            var failed = 0
            var written = 0

            for (feed in feedDao.enabled()) {
                if (!force && nowUtcMillis - feed.lastSyncMillis < REFRESH_INTERVAL_MILLIS) {
                    succeeded++
                    continue
                }
                when (val result = syncFeed(feed, nowUtcMillis, futureCutoffMillis)) {
                    is FeedResult.Success -> {
                        succeeded++
                        written += result.programmes
                        feedDao.markSynced(
                            feed.id,
                            nowUtcMillis,
                            "${result.programmes} programmes, ${result.channels} channels",
                        )
                    }
                    is FeedResult.Failed -> {
                        failed++
                        // The stamp is NOT advanced on failure, so the next sync retries
                        // rather than waiting out the interval on a feed that never landed.
                        feedDao.markSynced(feed.id, feed.lastSyncMillis, result.reason)
                        Log.w(TAG, "EPG feed ${feed.id} failed")
                    }
                }
            }

            if (succeeded > 0) {
                programmeDao.deleteEndedBefore(nowUtcMillis - RETENTION_PAST_MILLIS)
                programmeDao.deleteOutsideWindow(
                    pastCutoffMillis = nowUtcMillis - RETENTION_PAST_MILLIS,
                    futureCutoffMillis = futureCutoffMillis,
                )
            }

            val (matched, total) = runMatcher()
            SyncSummary(succeeded, failed, written, matched, total)
          }
        }

    private sealed interface FeedResult {
        data class Success(val programmes: Int, val channels: Int) : FeedResult
        data class Failed(val reason: String) : FeedResult
    }

    private suspend fun syncFeed(
        feed: EpgFeed,
        nowUtcMillis: Long,
        futureCutoffMillis: Long,
    ): FeedResult {
        val stagingFeedId = -feed.id
        val batch = ArrayList<Programme>(BATCH_SIZE)
        val aliases = ArrayList<EpgChannelAlias>(256)
        var written = 0
        var committed = false

        try {
            openFeedStream(feed).use { stream ->
                val stats = XmltvParser.parse(
                    input = stream,
                    feedId = stagingFeedId,
                    onChannelAlias = { id, displayName ->
                        aliases += EpgChannelAlias(
                            feedId = stagingFeedId,
                            epgId = id,
                            displayName = displayName ?: id,
                            normalizedKey = ChannelNameNormalizer.normalize(displayName ?: id).groupKey,
                        )
                    },
                    onProgramme = { programme ->
                        // Skip anything that finished before the retention cut-off; no point
                        // writing rows we are about to prune.
                        if (programme.endUtcMillis >= nowUtcMillis - RETENTION_PAST_MILLIS &&
                            programme.startUtcMillis <= futureCutoffMillis
                        ) {
                            batch += programme
                            if (batch.size >= BATCH_SIZE) {
                                programmeDao.upsertAll(batch)
                                written += batch.size
                                batch.clear()
                            }
                        }
                    },
                )
                if (batch.isNotEmpty()) {
                    programmeDao.upsertAll(batch)
                    written += batch.size
                }
                if (aliases.isNotEmpty()) {
                    aliases.chunked(BATCH_SIZE).forEach { aliasDao.upsertAll(it) }
                }

                if (written == 0) {
                    return FeedResult.Failed("Downloaded, but contained no programmes in the configured guide window.")
                }
                // The old guide is authoritative until the replacement is complete. This swap
                // covers programmes and aliases atomically, so readers never observe an empty or
                // half-written snapshot after a failed download.
                database.withTransaction {
                    programmeDao.deleteForFeed(feed.id)
                    aliasDao.deleteForFeed(feed.id)
                    programmeDao.moveFromFeed(stagingFeedId, feed.id)
                    aliasDao.moveFromFeed(stagingFeedId, feed.id)
                }
                committed = true
                return FeedResult.Success(written, stats.channelCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FeedResult.Failed(
                if (e is app.opentv.data.remote.HttpsRequiredException) HTTPS_REQUIRED_RESULT
                else "Guide download or parsing failed; the previous guide was kept.",
            )
        } finally {
            if (!committed) {
                withContext(NonCancellable) {
                    runCatching {
                        database.withTransaction {
                            programmeDao.deleteForFeed(stagingFeedId)
                            aliasDao.deleteForFeed(stagingFeedId)
                        }
                    }
                }
            }
        }
    }

    /**
     * Opens a feed as a decompressed XML stream.
     *
     * Several of the free guide sources publish `.gz` files (a national guide compresses
     * roughly 10:1), and providers occasionally gzip xmltv.php without saying so. Sniffing
     * the first two bytes for the gzip magic number handles both, regardless of what the
     * URL or the headers claim.
     */
    private suspend fun openFeedStream(feed: EpgFeed): InputStream {
        val raw: InputStream = when {
            feed.providerSourceId != null -> {
                val stored: Source = sourceDao.byId(feed.providerSourceId)
                    ?: throw IllegalStateException("Provider for this guide no longer exists.")
                val source = stored.copy(
                    url = HttpsUrlPolicy.withDefaultHttps(stored.url),
                    epgUrl = stored.epgUrl?.let(HttpsUrlPolicy::withDefaultHttps),
                )
                HttpsUrlPolicy.requireHttps(source.epgUrl?.takeIf { it.isNotBlank() } ?: source.url)
                api.openEpgStream(source)
            }
            feed.url != null -> {
                val secureUrl = HttpsUrlPolicy.withDefaultHttps(feed.url)
                try {
                    HttpsUrlPolicy.requireHttps(secureUrl)
                } catch (_: app.opentv.data.remote.HttpsRequiredException) {
                    throw IllegalStateException(HTTPS_REQUIRED_RESULT)
                }
                val trustedOriginUrl = when {
                    feed.builtIn -> null
                    feed.playlistSourceId != null -> sourceDao.byId(feed.playlistSourceId)?.takeIf {
                        NetworkDestinationPolicy.originOf(it.url) == NetworkDestinationPolicy.originOf(secureUrl)
                    }?.url
                    else -> secureUrl // A manually entered feed is an explicit user choice.
                }
                val scope = NetworkDestinationPolicy.scopeFor(secureUrl, trustedOriginUrl)
                    ?: throw IllegalStateException("Guide destination is not allowed.")
                val response = http.newCall(
                    Request.Builder()
                        .url(secureUrl)
                        .tag(NetworkDestinationPolicy.RequestScope::class.java, scope)
                        .build(),
                ).execute()
                if (!response.isSuccessful) {
                    response.close()
                    throw IllegalStateException("Guide download failed (HTTP ${response.code}).")
                }
                val body = response.body ?: throw IllegalStateException("The server returned an empty guide.")
                if (body.contentLength() > MAX_COMPRESSED_BYTES) {
                    response.close()
                    throw IllegalStateException("Guide download exceeds the safety limit.")
                }
                body.byteStream()
            }
            else -> throw IllegalStateException("Feed has no URL and no provider.")
        }

        val buffered = BufferedInputStream(
            BoundedInputStream(raw, MAX_COMPRESSED_BYTES, "Compressed XMLTV guide"),
            8 * 1024,
        )
        // Sniff the magic bytes: several free guides ship .gz, a few ship .xz, and providers
        // sometimes gzip without saying so. Covering all three keeps every declared URL usable.
        buffered.mark(6)
        val header = ByteArray(6)
        val read = buffered.read(header)
        buffered.reset()
        val expanded = when {
            read >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte() ->
                GZIPInputStream(buffered)
            read >= 6 && header[0] == 0xfd.toByte() && header[1] == 0x37.toByte() &&
                header[2] == 0x7a.toByte() && header[3] == 0x58.toByte() &&
                header[4] == 0x5a.toByte() && header[5] == 0x00.toByte() ->
                XZInputStream(buffered, MAX_XZ_MEMORY_KIB)
            else -> buffered
        }
        return BoundedInputStream(expanded, MAX_XML_BYTES, "Expanded XMLTV guide")
    }

    // ---- Matching --------------------------------------------------------------------------

    /**
     * Joins every channel to the merged guide by normalised name.
     * Returns (channels with a working guide id, total channels).
     */
    suspend fun runMatcher(): Pair<Int, Int> {
        val aliases = aliasDao.all()
        val index = EpgMatcher.buildIndex(aliases.map { it.epgId to it.displayName })
        // Only ids that actually have programmes count as "working" — a match against a
        // channel the guide lists but never fills is a guide that looks broken.
        val populated = programmeDao.channelIdsWithProgrammes().toHashSet()

        val channels = channelDao.allForMatching()
        var matched = 0

        for (channel in channels) {
            val newMatch = index.match(channel.groupKey)
            if (newMatch != channel.matchedEpgId) {
                channelDao.setMatchedEpgId(channel.id, newMatch)
            }
            val works = channel.epgCandidates.any { it in populated } ||
                (newMatch != null && newMatch in populated)
            if (works) matched++
        }

        Log.i(
            TAG,
            "Matcher: $matched of ${channels.size} channels have a working guide " +
                "(${aliases.size} guide aliases, ${populated.size} populated guide channels)",
        )
        return matched to channels.size
    }

    suspend fun setManualOverride(channelId: Long, epgId: String?) =
        channelDao.setEpgOverride(channelId, epgId)

    /**
     * Turns on the free guide for the user's region, once, on a pristine install.
     *
     * The reasoning: a provider guide is usually empty, and "go and find Guide settings"
     * is a hurdle most people meet as a blank guide and give up on. The app already knows
     * the region — the normaliser reads it off the `UK|` prefixes the provider itself
     * ships — so when a clear majority of channels agree, enable that region's built-in.
     *
     * Guard rails: only when every built-in is untouched (never enabled, never synced)
     * and no custom feed exists. Switch it off and it stays off — a choice the user has
     * made is never overridden.
     */
    private suspend fun maybeAutoEnableRegionalFeed() {
        val all = feedDao.all()
        val builtIns = all.filter { it.builtIn }
        if (builtIns.isEmpty()) return
        val pristine = builtIns.all { !it.enabled && it.lastSyncMillis == 0L } &&
            all.none { !it.builtIn && it.providerSourceId == null }
        if (!pristine) return

        val regionCounts = HashMap<String, Int>()
        for (channel in channelDao.allForMatching()) {
            val region = ChannelNameNormalizer.normalize(channel.name).region ?: continue
            regionCounts.merge(region, 1, Int::plus)
        }
        val top = regionCounts.maxByOrNull { it.value } ?: return
        if (top.value < MIN_CHANNELS_FOR_AUTO_REGION) return

        val feedName = REGION_TO_FEED[top.key] ?: return
        val feed = builtIns.firstOrNull { it.name == feedName } ?: return
        feedDao.setEnabled(feed.id, true)
        Log.i(TAG, "Auto-enabled '${feed.name}' — ${top.value} channels tagged ${top.key}")
    }

    companion object {
        private const val TAG = "EpgRepository"
        const val HTTPS_REQUIRED_RESULT = "HTTPS_REQUIRED"

        /** Writes per transaction. Large enough to be fast, small enough not to hold WAL open. */
        const val BATCH_SIZE = 500

        private const val MAX_COMPRESSED_BYTES = 256L * 1024 * 1024
        private const val MAX_XML_BYTES = 256L * 1024 * 1024
        private const val MAX_XZ_MEMORY_KIB = 64 * 1024

        /** Keep finished programmes for a day so "what was on" still works. */
        val RETENTION_PAST_MILLIS: Long = TimeUnit.DAYS.toMillis(1)

        /** Default/legacy value; active sync uses [retentionFutureMillis] from AppSettings. */
        val RETENTION_FUTURE_MILLIS: Long = TimeUnit.DAYS.toMillis(2)

        /** Feeds publish rolling windows; refreshing more often than this is rude. */
        val REFRESH_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(1)

        /**
         * The curated built-in list. Criteria for being here: free, no key or signup,
         * openly licensed or explicitly public, and reachable as plain XMLTV(.gz).
         * All ship disabled; the user turns on the one for where they live.
         *
         * The UK Freeview entry is GPL-3.0 (same licence as OpenTV), regenerated every
         * 12 h, ~270 channels with logos — verified working before it earned this slot.
         */
        /** A region needs at least this many prefixed channels before we act on it. */
        const val MIN_CHANNELS_FOR_AUTO_REGION = 5

        /** Region tag (as providers write it) → built-in feed name to auto-enable. */
        val REGION_TO_FEED: Map<String, String> = mapOf(
            "UK" to "UK — Freeview (free-to-air)",
            "GB" to "UK — Freeview (free-to-air)",
            "US" to "USA — epgshare01",
            "USA" to "USA — epgshare01",
            "CA" to "Canada — epgshare01",
            "AU" to "Australia — epgshare01",
            "AUS" to "Australia — epgshare01",
        )

        val BUILT_IN_FEEDS: List<Pair<String, String>> = listOf(
            "UK — Freeview (free-to-air)" to
                "https://raw.githubusercontent.com/dp247/Freeview-EPG/master/epg.xml",
            "UK — epgshare01 (Sky lineup)" to
                "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
            "USA — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz",
            "Canada — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_CA1.xml.gz",
            "Australia — epgshare01" to
                "https://epgshare01.online/epgshare01/epg_ripper_AU1.xml.gz",
        )
    }
}
