/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import kotlinx.serialization.Serializable

/**
 * The state two OpenTV devices exchange when they sync.
 *
 * Nothing here is keyed by a local database id — those differ from one install to the next. Stream
 * URLs are represented by keyed HMAC identifiers; raw URLs, provider passwords and NAS hosts are
 * never written to a rendezvous bundle. The group key is present only in a pinned TLS exchange or
 * inside the user's encrypted backup, never in NAS JSON.
 *
 * Two merge policies travel together, matching how people actually use this:
 *  - **Watch positions** are two-way, newest-wins per item (you watch on any device, resume on
 *    all), using [SyncPosition.updatedAtMillis].
 *  - **Curation and recordings** are additive: favourites, hidden channels, hidden categories and
 *    NAS recordings from a peer are merged in without ever removing anything local. You tidy up on
 *    one device and the others adopt it, but a favourite set only on one box is never lost.
 *
 * Version 4 intentionally does not consume version 3 bundles, which stored raw URLs. It is safer to
 * skip an old file than to keep importing credentials from it.
 */
@Serializable
data class SyncBundle(
    val version: Int = 4,
    /** Public, non-secret check that NAS peers share the same HMAC key. */
    val groupId: String = "",
    /** Secret group key; filled only for pinned TLS sync and encrypted FullTV backups. */
    val syncGroupKeyBase64: String = "",
    val generatedAtMillis: Long = 0,
    /** Profile names the peer knows about. Others add any they are missing (add-only). */
    val profiles: List<String> = emptyList(),
    /** Two-way, newest-wins resume points. */
    val positions: List<SyncPosition> = emptyList(),
    /** Additive curation, keyed by the stable identifiers noted above. */
    val favouriteChannels: List<String> = emptyList(),
    val hiddenChannels: List<String> = emptyList(),
    val favouriteMovies: List<String> = emptyList(),
    /** Favourite series, keyed by the provider's own series id (series carry no stream URL). */
    val favouriteSeries: List<String> = emptyList(),
    val hiddenCategories: List<String> = emptyList(),
    /**
     * Recordings that live on the shared NAS (their [SyncRecording.filePath] is an `smb://`
     * locator) and so play on any device pointed at the same NAS. Device-local recordings are
     * never shared — they cannot be opened from another box.
     */
    val recordings: List<SyncRecording> = emptyList(),
)

@Serializable
data class SyncPosition(
    val profile: String,
    /** Keyed HMAC of a stream URL; not reversible without the private sync-group key. */
    val mediaKey: String = "",
    /** "movie" or "ep" — which table to resolve [mediaKey] against on the receiving device. */
    val kind: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * A finished NAS recording, portable across devices. Everything needed to insert a playable
 * library row is snapshotted here; the receiving device opens [filePath] (an `smb://` locator)
 * straight off the shared NAS. De-duplicated on the receiver by that locator.
 */
@Serializable
data class SyncRecording(
    val channelName: String,
    val title: String,
    val description: String? = null,
    /** Path relative to the configured recordings folder; never carries a NAS host or share. */
    val relativePath: String = "",
    val scheduledStartMillis: Long = 0,
    val scheduledEndMillis: Long = 0,
    val startedAtMillis: Long = 0,
    val endedAtMillis: Long = 0,
    val sizeBytes: Long = 0,
)
