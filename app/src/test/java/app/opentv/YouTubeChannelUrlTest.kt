/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.player.YouTubeChannelUrl
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YouTubeChannelUrlTest {
    @Test
    fun `bare handle becomes the current live route and ignores query parameters`() {
        assertThat(
            YouTubeChannelUrl.asLiveUrlOrNull("https://www.youtube.com/@tvbrasil?feature=shared"),
        ).isEqualTo("https://www.youtube.com/@tvbrasil/live")
    }

    @Test
    fun `existing live route stays normalized and secure`() {
        assertThat(
            YouTubeChannelUrl.asLiveUrlOrNull("http://m.youtube.com/@news/live/"),
        ).isEqualTo("https://www.youtube.com/@news/live")
    }

    @Test
    fun `watch result URLs are canonicalized for Streamlink`() {
        assertThat(
            YouTubeChannelUrl.asLiveUrlOrNull("https://www.youtube.com/watch?feature=share&v=abcdefghijk"),
        ).isEqualTo("https://www.youtube.com/watch?v=abcdefghijk")
        assertThat(
            YouTubeChannelUrl.asLiveUrlOrNull("https://youtu.be/abcdefghijk?t=10"),
        ).isEqualTo("https://www.youtube.com/watch?v=abcdefghijk")
        assertThat(
            YouTubeChannelUrl.asLiveUrlOrNull("https://www.youtube.com/live/abcdefghijk"),
        ).isEqualTo("https://www.youtube.com/live/abcdefghijk")
    }

    @Test
    fun `unrelated YouTube pages and similar hosts are not intercepted`() {
        assertThat(YouTubeChannelUrl.asLiveUrlOrNull("https://www.youtube.com/@tvbrasil/videos"))
            .isNull()
        assertThat(YouTubeChannelUrl.asLiveUrlOrNull("https://www.youtube.com/watch?v=short"))
            .isNull()
        assertThat(YouTubeChannelUrl.asLiveUrlOrNull("https://youtube.com.evil.example/@tvbrasil"))
            .isNull()
        assertThat(YouTubeChannelUrl.asLiveUrlOrNull("https://example.com/@tvbrasil"))
            .isNull()
    }

    @Test
    fun `malformed URLs are rejected`() {
        assertThat(YouTubeChannelUrl.asLiveUrlOrNull("not a URL")).isNull()
        assertThat(YouTubeChannelUrl.asLiveUrlOrNull("https://www.youtube.com/@")).isNull()
        assertThat(YouTubeChannelUrl.asLiveUrlOrNull("ftp://www.youtube.com/@tvbrasil")).isNull()
        assertThat(YouTubeChannelUrl.asLiveUrlOrNull("https://www.youtube.com/watch?v=abc&v=def")).isNull()
    }
}
