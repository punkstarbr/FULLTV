/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuideGridNavigationPolicyTest {
    private fun slot(key: String, start: Long, duration: Long = 30): GuideProgrammeSlot =
        GuideProgrammeSlot(key, start, start + duration)

    @Test
    fun `left and right follow programme order including gaps`() {
        val rows = listOf(listOf(slot("morning", 0), slot("evening", 10_000)))

        assertThat(guideProgrammeTarget(rows, 0, 0, GuideProgrammeDirection.RIGHT))
            .isEqualTo(GuideProgrammeTarget(0, 1))
        assertThat(guideProgrammeTarget(rows, 0, 1, GuideProgrammeDirection.LEFT))
            .isEqualTo(GuideProgrammeTarget(0, 0))
    }

    @Test
    fun `horizontal edges defer to Compose for channel column movement`() {
        val rows = listOf(listOf(slot("only", 100)))

        assertThat(guideProgrammeTarget(rows, 0, 0, GuideProgrammeDirection.LEFT)).isNull()
        assertThat(guideProgrammeTarget(rows, 0, 0, GuideProgrammeDirection.RIGHT)).isNull()
    }

    @Test
    fun `vertical movement selects the closest time centre in adjacent row`() {
        val rows = listOf(
            listOf(slot("source", 10_000, 60)),
            listOf(slot("early", 8_000, 30), slot("aligned", 10_020, 60), slot("late", 20_000, 30)),
        )

        assertThat(guideProgrammeTarget(rows, 0, 0, GuideProgrammeDirection.DOWN))
            .isEqualTo(GuideProgrammeTarget(1, 1))
        assertThat(guideProgrammeTarget(rows, 1, 1, GuideProgrammeDirection.UP))
            .isEqualTo(GuideProgrammeTarget(0, 0))
    }

    @Test
    fun `missing or empty adjacent row defers to Compose`() {
        val rows = listOf(listOf(slot("only", 100)), emptyList())

        assertThat(guideProgrammeTarget(rows, 0, 0, GuideProgrammeDirection.UP)).isNull()
        assertThat(guideProgrammeTarget(rows, 0, 0, GuideProgrammeDirection.DOWN)).isNull()
        assertThat(guideProgrammeTarget(rows, -1, 0, GuideProgrammeDirection.RIGHT)).isNull()
        assertThat(guideProgrammeTarget(rows, 0, 5, GuideProgrammeDirection.RIGHT)).isNull()
    }

    @Test
    fun `focus key separates identical programme across channel rows`() {
        assertThat(guideProgrammeFocusKey("row-a", "feed:epg:100"))
            .isNotEqualTo(guideProgrammeFocusKey("row-b", "feed:epg:100"))
    }
}
