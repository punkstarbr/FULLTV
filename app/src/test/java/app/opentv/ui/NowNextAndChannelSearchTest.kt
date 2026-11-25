/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.data.model.Programme
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NowNextAndChannelSearchTest {
    @Test
    fun `channel search accepts a single digit but not a single letter`() {
        assertThat(isChannelSearchReady("")).isFalse()
        assertThat(isChannelSearchReady(" ")).isFalse()
        assertThat(isChannelSearchReady("1")).isTrue()
        assertThat(isChannelSearchReady("12")).isTrue()
        assertThat(isChannelSearchReady("b")).isFalse()
        assertThat(isChannelSearchReady("bbc")).isTrue()
    }

    @Test
    fun `now and next move forward when time crosses a programme boundary`() {
        val programmes = listOf(
            programme(start = 0L, end = 600L, title = "Morning news"),
            programme(start = 600L, end = 1_200L, title = "Cartoons"),
            programme(start = 1_200L, end = 1_800L, title = "Movie"),
        )

        val beforeBoundary = selectCurrentAndNextProgramme(programmes, nowUtcMillis = 599L)
        assertThat(beforeBoundary.current?.title).isEqualTo("Morning news")
        assertThat(beforeBoundary.next?.title).isEqualTo("Cartoons")

        val exactlyAtBoundary = selectCurrentAndNextProgramme(programmes, nowUtcMillis = 600L)
        assertThat(exactlyAtBoundary.current?.title).isEqualTo("Cartoons")
        assertThat(exactlyAtBoundary.next?.title).isEqualTo("Movie")
    }

    @Test
    fun `now and next report the next future programme during a gap`() {
        val programmes = listOf(
            programme(start = 0L, end = 600L, title = "Earlier"),
            programme(start = 900L, end = 1_500L, title = "Upcoming"),
        )

        val selected = selectCurrentAndNextProgramme(programmes, nowUtcMillis = 700L)
        assertThat(selected.current).isNull()
        assertThat(selected.next?.title).isEqualTo("Upcoming")
    }

    private fun programme(start: Long, end: Long, title: String) = Programme(
        feedId = 1L,
        epgChannelId = "test-channel",
        startUtcMillis = start,
        endUtcMillis = end,
        title = title,
    )
}
