/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.EpgFeed
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpgFeedPriorityTest {
    @Test
    fun `lower priority number is first and equal values use stable feed id`() {
        val feeds = listOf(
            feed(id = 9, priority = 4),
            feed(id = 3, priority = 1),
            feed(id = 5, priority = 1),
        )

        assertThat(EpgFeedPriority.ordered(feeds).map { it.id }).containsExactly(3L, 5L, 9L).inOrder()
    }

    @Test
    fun `moving a feed higher compacts the new order`() {
        val feeds = listOf(
            feed(id = 10, priority = 10),
            feed(id = 20, priority = 20),
            feed(id = 30, priority = 30),
        )

        assertThat(EpgFeedPriority.reorderedPriorities(feeds, feedId = 30, direction = -1))
            .containsExactly(10L to 0, 30L to 1, 20L to 2)
            .inOrder()
    }

    @Test
    fun `moving a feed lower compacts the new order`() {
        val feeds = listOf(
            feed(id = 10, priority = 0),
            feed(id = 20, priority = 1),
            feed(id = 30, priority = 2),
        )

        assertThat(EpgFeedPriority.reorderedPriorities(feeds, feedId = 10, direction = 1))
            .containsExactly(20L to 0, 10L to 1, 30L to 2)
            .inOrder()
    }

    @Test
    fun `move outside list or missing feed is a no-op`() {
        val feeds = listOf(feed(id = 10, priority = 0), feed(id = 20, priority = 1))

        assertThat(EpgFeedPriority.reorderedPriorities(feeds, feedId = 10, direction = -1)).isEmpty()
        assertThat(EpgFeedPriority.reorderedPriorities(feeds, feedId = 99, direction = 1)).isEmpty()
    }

    private fun feed(id: Long, priority: Int) = EpgFeed(
        id = id,
        name = "guide-$id",
        priority = priority,
    )
}
