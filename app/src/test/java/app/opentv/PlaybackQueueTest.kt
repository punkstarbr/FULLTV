/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.player.PlaybackQueue
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

class PlaybackQueueTest {
    @After
    fun clearGlobalHandoff() {
        PlaybackQueue.clear()
    }

    @Test
    fun `matching launch consumes its ordered channel queue exactly once`() {
        val items = listOf(
            PlaybackQueue.Item(11, "First", null, 1),
            PlaybackQueue.Item(12, "Second", null, 2),
        )
        PlaybackQueue.publish(11, items)

        assertThat(PlaybackQueue.consumeFor(11)).containsExactlyElementsIn(items).inOrder()
        assertThat(PlaybackQueue.consumeFor(11)).isEmpty()
    }

    @Test
    fun `stale queue for a different launch is discarded`() {
        PlaybackQueue.publish(11, listOf(PlaybackQueue.Item(11, "Old", null)))

        assertThat(PlaybackQueue.consumeFor(22)).isEmpty()
        assertThat(PlaybackQueue.consumeFor(11)).isEmpty()
    }
}
