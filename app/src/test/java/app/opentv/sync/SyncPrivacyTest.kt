/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class SyncPrivacyTest {
    private val url = "https://provider.example/live/user123/password-secret/99.ts"
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `same group and media identity produce a stable opaque HMAC`() {
        val first = SyncPrivacy.mediaKey(key, "movie", url)
        val second = SyncPrivacy.mediaKey(key.copyOf(), "movie", url)

        assertThat(first).isEqualTo(second)
        assertThat(first).hasLength(64)
        assertThat(first).doesNotContain("password-secret")
        assertThat(first).doesNotContain("provider.example")
    }

    @Test
    fun `HMAC changes when the group key media kind or URL changes`() {
        val base = SyncPrivacy.mediaKey(key, "movie", url)

        assertThat(SyncPrivacy.mediaKey(ByteArray(32) { 7 }, "movie", url)).isNotEqualTo(base)
        assertThat(SyncPrivacy.mediaKey(key, "ep", url)).isNotEqualTo(base)
        assertThat(SyncPrivacy.mediaKey(key, "movie", url + "?other=1")).isNotEqualTo(base)
    }

    @Test
    fun `group fingerprint is short and does not reveal the key`() {
        val id = SyncPrivacy.groupId(key)

        assertThat(id).hasLength(24)
        assertThat(id).doesNotContain(key.joinToString())
    }

    @Test
    fun `serialized v4 bundle contains HMAC but never raw provider URL`() {
        val opaque = SyncPrivacy.mediaKey(key, "movie", url)
        val bundle = SyncBundle(
            groupId = SyncPrivacy.groupId(key),
            positions = listOf(
                SyncPosition(
                    profile = "Living room",
                    mediaKey = opaque,
                    kind = "movie",
                    positionMillis = 1234,
                    durationMillis = 4567,
                    updatedAtMillis = 99,
                ),
            ),
        )
        val encoded = Json { encodeDefaults = true }.encodeToString(SyncBundle.serializer(), bundle)

        assertThat(encoded).contains(opaque)
        assertThat(encoded).doesNotContain("provider.example")
        assertThat(encoded).doesNotContain("password-secret")
        assertThat(encoded).doesNotContain("user123")
    }
}
