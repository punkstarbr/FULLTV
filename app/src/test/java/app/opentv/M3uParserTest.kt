/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.M3uParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uParserTest {
    @Test
    fun `parses a well formed HTTPS playlist and retains source metadata`() {
        val playlist = """
            #EXTM3U url-tvg="https://example.com/xmltv.php?username=u&password=p"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="https://img/bbc1.png" group-title="UK",BBC One HD
            https://example.com:8080/live/u/p/1.m3u8
            #EXTINF:-1 tvg-id="itv1.uk" tvg-logo="https://img/itv.png" group-title="UK",ITV 1
            https://example.com:8080/live/u/p/2.m3u8
        """.trimIndent()
        val result = M3uParser.parse(playlist, sourceId = 7)
        assertThat(result.channels).hasSize(2)
        assertThat(result.declaredEpgUrl).isEqualTo("https://example.com/xmltv.php?username=u&password=p")
        val first = result.channels.first()
        assertThat(first.name).isEqualTo("BBC One HD")
        assertThat(first.epgChannelId).isEqualTo("bbc1.uk")
        assertThat(first.categoryId).isEqualTo("UK")
        assertThat(first.logoUrl).isEqualTo("https://img/bbc1.png")
        assertThat(first.sourceId).isEqualTo(7)
        assertThat(first.streamUrl).isEqualTo("https://example.com:8080/live/u/p/1.m3u8")
    }

    @Test
    fun `rejects HTTP local file and userinfo stream URLs`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,HTTP stream
            http://example.com/live.m3u8
            #EXTINF:-1,Local file
            file:///data/data/app.fulltv/private.db
            #EXTINF:-1,Authority credentials
            https://user:password@example.com/live.m3u8
            #EXTINF:-1,Safe stream
            https://example.com/good.m3u8
        """.trimIndent()
        val result = M3uParser.parse(playlist, sourceId = 1)
        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().streamUrl).isEqualTo("https://example.com/good.m3u8")
        assertThat(result.skippedEntries).isEqualTo(3)
    }

    @Test
    fun `a URL with no EXTINF is skipped without losing the rest`() {
        val playlist = """
            #EXTM3U
            https://example.com/orphan.m3u8
            #EXTINF:-1 tvg-id="good",Good Channel
            https://example.com/good.m3u8
        """.trimIndent()
        val result = M3uParser.parse(playlist, sourceId = 1)
        assertThat(result.skippedEntries).isEqualTo(1)
        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().name).isEqualTo("Good Channel")
    }

    @Test
    fun `falls back to tvg-name when the display name after the comma is empty`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" tvg-name="Fallback Name",
            https://example.com/x.m3u8
        """.trimIndent()
        assertThat(M3uParser.parse(playlist, sourceId = 1).channels.single().name).isEqualTo("Fallback Name")
    }

    @Test
    fun `same tvg-id retains distinct stream variants in playlist order`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup",Channel SD
            https://example.com/sd.m3u8
            #EXTINF:-1 tvg-id="dup",Channel HD
            https://example.com/hd.m3u8
        """.trimIndent()
        val result = M3uParser.parse(playlist, sourceId = 1)
        assertThat(result.channels).hasSize(2)
        assertThat(result.channels.map { it.name }).containsExactly("Channel SD", "Channel HD").inOrder()
        assertThat(result.channels.map { it.epgChannelId }).containsExactly("dup", "dup").inOrder()
        assertThat(result.channels.map { it.streamId }.distinct()).hasSize(2)
        assertThat(result.channels.map { it.sortIndex }).containsExactly(0, 1).inOrder()
    }

    @Test
    fun `same tvg-id and exact same URL is deduplicated`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup",Channel
            https://example.com/live.m3u8
            #EXTINF:-1 tvg-id="dup",Channel duplicate
            https://example.com/live.m3u8
        """.trimIndent()
        val result = M3uParser.parse(playlist, sourceId = 1)
        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().name).isEqualTo("Channel")
    }

    @Test
    fun `channels without tvg id receive a stable URL based id`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,No Id Channel
            https://example.com/noid.m3u8
        """.trimIndent()
        val first = M3uParser.parse(playlist, sourceId = 1).channels.single()
        val second = M3uParser.parse(playlist, sourceId = 1).channels.single()
        assertThat(first.streamId).startsWith("url:")
        assertThat(first.streamId).isEqualTo(second.streamId)
        assertThat(first.epgChannelId).isNull()
    }

    @Test
    fun `unknown directives between EXTINF and URL are ignored`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            #EXTVLCOPT:http-user-agent=SomePlayer/1.0
            #EXTHTTP:{"User-Agent":"SomePlayer/1.0"}
            https://example.com/a.m3u8
        """.trimIndent()
        val channels = M3uParser.parse(playlist, sourceId = 1).channels
        assertThat(channels).hasSize(1)
        assertThat(channels.single().streamUrl).isEqualTo("https://example.com/a.m3u8")
    }

    @Test
    fun `channel numbers come from tvg-chno`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" tvg-chno="101",Channel A
            https://example.com/a.m3u8
        """.trimIndent()
        assertThat(M3uParser.parse(playlist, sourceId = 1).channels.single().number).isEqualTo(101)
    }

    @Test
    fun `an empty playlist yields no channels`() {
        val result = M3uParser.parse("#EXTM3U\n", sourceId = 1)
        assertThat(result.channels).isEmpty()
        assertThat(result.skippedEntries).isEqualTo(0)
    }

    @Test
    fun `collects every bounded guide URL declared on the header`() {
        val playlist = """
            #EXTM3U url-tvg="https://a.example/x.xml" url-tvg="https://b.example/y.xml.gz" url-tvg="https://c.example/z.xml https://d.example/w.xml.gz http://insecure.example/x.xml"
            #EXTINF:-1,Channel A
            https://example.com/a.m3u8
        """.trimIndent()
        val result = M3uParser.parse(playlist, sourceId = 1)
        assertThat(result.declaredEpgUrls).containsExactly(
            "https://a.example/x.xml", "https://b.example/y.xml.gz",
            "https://c.example/z.xml", "https://d.example/w.xml.gz",
        ).inOrder()
        assertThat(result.declaredEpgUrl).isEqualTo("https://a.example/x.xml")
    }

    @Test
    fun `duplicate guide URLs are collapsed`() {
        val playlist = """
            #EXTM3U x-tvg-url="https://a.example/x.xml" url-tvg="https://a.example/x.xml"
            #EXTINF:-1,Channel A
            https://example.com/a.m3u8
        """.trimIndent()
        assertThat(M3uParser.parse(playlist, sourceId = 1).declaredEpgUrls)
            .containsExactly("https://a.example/x.xml")
    }

    @Test
    fun `guide URL attributes are discovered anywhere in the playlist`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="one" url-tvg="https://a.example/guide.xml",Channel One
            https://example.com/one.m3u8
            #EXTGRP x-tvg-url="https://b.example/guide.xml https://c.example/guide.xml"
            #EXTINF:-1 tvg-id="two",Channel Two
            https://example.com/two.m3u8
        """.trimIndent()
        val result = M3uParser.parse(playlist, sourceId = 1)
        assertThat(result.channels).hasSize(2)
        assertThat(result.declaredEpgUrls).containsExactly(
            "https://a.example/guide.xml", "https://b.example/guide.xml", "https://c.example/guide.xml",
        ).inOrder()
    }

    @Test
    fun `playlist order is preserved even when channel numbers are not ascending`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-chno="200",Zulu
            https://example.com/zulu.m3u8
            #EXTINF:-1 tvg-chno="10",Alpha
            https://example.com/alpha.m3u8
            #EXTINF:-1 tvg-chno="11",Bravo
            https://example.com/bravo.m3u8
        """.trimIndent()
        val channels = M3uParser.parse(playlist, sourceId = 1).channels
        assertThat(channels.map { it.displayName }).containsExactly("Zulu", "Alpha", "Bravo").inOrder()
        assertThat(channels.map { it.sortIndex }).containsExactly(0, 1, 2).inOrder()
        assertThat(channels.map { it.number }).containsExactly(200, 10, 11).inOrder()
    }

    @Test
    fun `unbounded individual lines fail before excessive allocation`() {
        val playlist = "#EXTM3U\n#EXTINF:-1,${"x".repeat(20_000)}\nhttps://example.com/live.m3u8"
        assertThat(runCatching { M3uParser.parse(playlist, sourceId = 1) }.isFailure).isTrue()
    }
}
