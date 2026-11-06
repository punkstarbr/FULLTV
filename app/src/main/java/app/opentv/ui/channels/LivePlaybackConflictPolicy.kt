/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

/**
 * A provider's connection quota is relevant only when live playback is another stream from the
 * same configured source. YouTube lives use YouTube delivery, not an IPTV provider account quota.
 * The provider may still impose its own limit; the UI lets the user choose whether to try.
 */
internal object LivePlaybackConflictPolicy {
    fun needsProviderWarning(
        isYouTubeLive: Boolean,
        playbackSourceId: Long,
        activeRecordingSourceIds: Collection<Long>,
    ): Boolean = !isYouTubeLive && activeRecordingSourceIds.any { it == playbackSourceId }
}
