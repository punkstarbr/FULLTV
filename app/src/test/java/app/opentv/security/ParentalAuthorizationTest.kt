/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.security

import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.StreamKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParentalAuthorizationTest {

    @Test
    fun `hidden category is resolved from its logical key`() {
        val categories = listOf(
            Category("adult", 1L, "Adult | HD", StreamKind.LIVE),
            Category("news", 1L, "News", StreamKind.LIVE),
        )

        assertThat(ParentalAuthorization.hiddenCategoryIds(categories, setOf("adult")))
            .containsExactly("adult")
    }

    @Test
    fun `pin blocks reactivating a hidden channel until session unlock`() {
        assertThat(ParentalAuthorization.mayUnhide(pinIsSet = true, hiddenUnlocked = false)).isFalse()
        assertThat(ParentalAuthorization.mayUnhide(pinIsSet = true, hiddenUnlocked = true)).isTrue()
        assertThat(ParentalAuthorization.mayUnhide(pinIsSet = false, hiddenUnlocked = false)).isTrue()
    }

    @Test
    fun `playback check covers direct routes and hidden categories`() {
        assertThat(
            ParentalAuthorization.mayStartPlayback(
                channelHidden = false,
                categoryHidden = true,
                pinIsSet = true,
                hiddenUnlocked = false,
            ),
        ).isFalse()
        assertThat(
            ParentalAuthorization.mayStartPlayback(
                channelHidden = true,
                categoryHidden = false,
                pinIsSet = true,
                hiddenUnlocked = false,
            ),
        ).isFalse()
        assertThat(
            ParentalAuthorization.mayStartPlayback(
                channelHidden = true,
                categoryHidden = true,
                pinIsSet = true,
                hiddenUnlocked = true,
            ),
        ).isTrue()
    }

    @Test
    fun `channel overload uses category ids and channel hidden flag`() {
        val channel = Channel(
            sourceId = 1L,
            streamId = "1",
            name = "Adult channel",
            categoryId = "adult",
            logoUrl = null,
            epgChannelId = null,
            number = null,
            streamUrl = "https://example.test/live/1",
            hidden = true,
        )

        assertThat(
            ParentalAuthorization.mayStartPlayback(
                channel,
                hiddenCategoryIds = setOf("adult"),
                pinIsSet = true,
                hiddenUnlocked = false,
            ),
        ).isFalse()
    }
}
