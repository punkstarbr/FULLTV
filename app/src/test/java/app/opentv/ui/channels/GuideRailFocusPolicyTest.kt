/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuideRailFocusPolicyTest {
    @Test
    fun `pinned entries and category indices account for optional source section`() {
        assertThat(guideRailSelectedItemIndex(1, 3, null, favouritesOnly = true)).isEqualTo(0)
        assertThat(guideRailSelectedItemIndex(1, 3, null, favouritesOnly = false)).isEqualTo(1)
        assertThat(guideRailSelectedItemIndex(1, 3, 0, favouritesOnly = false)).isEqualTo(2)
        assertThat(guideRailSelectedItemIndex(1, 3, 2, favouritesOnly = false)).isEqualTo(4)

        // Header + all-sources + three sources + divider precede the pinned rail entries.
        assertThat(guideRailSelectedItemIndex(3, 3, null, favouritesOnly = true)).isEqualTo(6)
        assertThat(guideRailSelectedItemIndex(3, 3, null, favouritesOnly = false)).isEqualTo(7)
        assertThat(guideRailSelectedItemIndex(3, 3, 0, favouritesOnly = false)).isEqualTo(8)
        assertThat(guideRailSelectedItemIndex(3, 3, 2, favouritesOnly = false)).isEqualTo(10)
    }

    @Test
    fun `missing category falls back to all instead of requesting a stale list item`() {
        assertThat(guideRailSelectedItemIndex(2, 0, null, favouritesOnly = false)).isEqualTo(6)
        assertThat(guideRailSelectedItemIndex(2, 2, -1, favouritesOnly = false)).isEqualTo(6)
        assertThat(guideRailSelectedItemIndex(2, 2, 2, favouritesOnly = false)).isEqualTo(6)
    }
}
