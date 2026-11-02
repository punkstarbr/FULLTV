/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.pairing.PairingHttp
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test

/**
 * The pairing server accepts IPTV credentials over the network. Its parsing and its access
 * checks are the parts worth testing hardest — everything else is sockets.
 */
class PairingHttpTest {

    @Test
    fun `parses a request line with a query string`() {
        val parsed = PairingHttp.parseRequestLine("GET /?t=abc123 HTTP/1.1")

        assertThat(parsed).isNotNull()
        val (method, path, query) = parsed!!
        assertThat(method).isEqualTo("GET")
        assertThat(path).isEqualTo("/")
        assertThat(query["t"]).isEqualTo("abc123")
    }

    @Test
    fun `parses a request line without a query string`() {
        val (method, path, query) = PairingHttp.parseRequestLine("POST /submit HTTP/1.1")!!

        assertThat(method).isEqualTo("POST")
        assertThat(path).isEqualTo("/submit")
        assertThat(query).isEmpty()
    }

    @Test
    fun `rubbish request lines are rejected rather than throwing`() {
        assertThat(PairingHttp.parseRequestLine("")).isNull()
        assertThat(PairingHttp.parseRequestLine("GET")).isNull()
    }

    @Test
    fun `decodes form bodies including the awkward characters in passwords`() {
        // Real provider passwords contain +, &, = and spaces, and every one of them means
        // something else in a form body.
        val body = "username=lee%40example.com&password=p%40ss%2Bw%26rd%3D1&name=Living+Room"

        val form = PairingHttp.parseFormEncoded(body)

        assertThat(form["username"]).isEqualTo("lee@example.com")
        assertThat(form["password"]).isEqualTo("p@ss+w&rd=1")
        assertThat(form["name"]).isEqualTo("Living Room")
    }

    @Test
    fun `a malformed pair does not discard the rest of the form`() {
        val form = PairingHttp.parseFormEncoded("a=1&&=nokey&b=2")

        assertThat(form["a"]).isEqualTo("1")
        assertThat(form["b"]).isEqualTo("2")
    }

    @Test
    fun `empty values are preserved rather than dropped`() {
        val form = PairingHttp.parseFormEncoded("epg=&url=http%3A%2F%2Fx.com")

        assertThat(form["epg"]).isEmpty()
        assertThat(form["url"]).isEqualTo("http://x.com")
    }

    @Test
    fun `header names are lowercased`() {
        assertThat(PairingHttp.parseHeader("Content-Length: 42"))
            .isEqualTo("content-length" to "42")
        assertThat(PairingHttp.parseHeader("not a header")).isNull()
    }

    @Test
    fun `secret comparison rejects mismatches and length differences`() {
        assertThat(PairingHttp.secretsMatch("abc123", "abc123")).isTrue()
        assertThat(PairingHttp.secretsMatch("abc123", "abc124")).isFalse()
        assertThat(PairingHttp.secretsMatch("abc", "abc123")).isFalse()
        assertThat(PairingHttp.secretsMatch("", "")).isTrue()
    }

    @Test
    fun `private addresses are recognised`() {
        assertThat(PairingHttp.isPrivateAddress("192.168.0.14")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("10.0.0.5")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("172.16.4.1")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("172.31.255.254")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("169.254.10.1")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("127.0.0.1")).isTrue()
        assertThat(PairingHttp.isPrivateAddress("fd00::1")).isTrue()
    }

    @Test
    fun `public addresses are refused`() {
        // The pairing form takes IPTV credentials. If a router ever exposes it, the request
        // must be turned away rather than served.
        assertThat(PairingHttp.isPrivateAddress("8.8.8.8")).isFalse()
        assertThat(PairingHttp.isPrivateAddress("172.32.0.1")).isFalse()
        assertThat(PairingHttp.isPrivateAddress("172.15.0.1")).isFalse()
        assertThat(PairingHttp.isPrivateAddress("193.168.0.1")).isFalse()
        assertThat(PairingHttp.isPrivateAddress("2606:4700::1")).isFalse()
        assertThat(PairingHttp.isPrivateAddress(null)).isFalse()
        assertThat(PairingHttp.isPrivateAddress("")).isFalse()
    }

    @Test
    fun `browser mutations accept only the exact HTTPS origin`() {
        val authority = "192.168.1.10:43210"

        assertThat(PairingHttp.isExpectedOrigin("https://$authority", authority)).isTrue()
        assertThat(PairingHttp.isExpectedOrigin("http://$authority", authority)).isFalse()
        assertThat(PairingHttp.isExpectedOrigin("https://evil.example", authority)).isFalse()
        assertThat(PairingHttp.isExpectedOrigin("https://$authority/", authority)).isFalse()
        assertThat(PairingHttp.isExpectedOrigin(null, authority)).isFalse()
    }

    @Test
    fun `responses carry a correct byte length for multibyte content`() {
        val response = PairingHttp.httpResponse("200 OK", "text/html", "café ☕")
        val text = String(response, Charsets.UTF_8)

        // Content-Length is bytes, not characters. Getting this wrong truncates the page in
        // exactly the sort of way that only shows up for people whose provider name has an
        // accent in it.
        assertThat(text).contains("Content-Length: ${"café ☕".toByteArray(Charsets.UTF_8).size}")
        assertThat(text).contains("no-store")
    }

    @Test
    fun `reads a bounded HTTP 1_1 form request by byte length`() {
        val body = "username=Jos%C3%A9"
        val raw = "POST /submit HTTP/1.1\r\nHost: 192.168.1.10:43210\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"

        val request = PairingHttp.readRequest(ByteArrayInputStream(raw.toByteArray()), 1024)

        assertThat(request?.method).isEqualTo("POST")
        assertThat(request?.path).isEqualTo("/submit")
        assertThat(request?.headers?.get("host")).isEqualTo("192.168.1.10:43210")
        assertThat(PairingHttp.parseFormEncodedStrict(request!!.body)?.get("username")).isEqualTo("José")
    }

    @Test
    fun `rejects chunked and duplicate content length framing`() {
        val chunked = "POST /submit HTTP/1.1\r\nHost: tv:4000\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
        val duplicateLength = "POST /submit HTTP/1.1\r\nHost: tv:4000\r\n" +
            "Content-Length: 0\r\nContent-Length: 0\r\n\r\n"

        val chunkedError = runCatching {
            PairingHttp.readRequest(ByteArrayInputStream(chunked.toByteArray()), 1024)
        }.exceptionOrNull() as PairingHttp.RequestException
        val duplicateError = runCatching {
            PairingHttp.readRequest(ByteArrayInputStream(duplicateLength.toByteArray()), 1024)
        }.exceptionOrNull() as PairingHttp.RequestException

        assertThat(chunkedError.status).isEqualTo("400 Bad Request")
        assertThat(duplicateError.status).isEqualTo("400 Bad Request")
    }

    @Test
    fun `rejects request body above the endpoint limit`() {
        val body = "a=12345"
        val raw = "POST /submit HTTP/1.1\r\nHost: tv:4000\r\nContent-Length: ${body.length}\r\n\r\n$body"

        val error = runCatching {
            PairingHttp.readRequest(ByteArrayInputStream(raw.toByteArray()), 4)
        }.exceptionOrNull() as PairingHttp.RequestException

        assertThat(error.status).isEqualTo("413 Content Too Large")
    }

    @Test
    fun `rejects duplicate query credentials and malformed line endings`() {
        assertThat(PairingHttp.parseRequestLine("GET /?t=first&t=second HTTP/1.1")).isNull()
        val raw = "GET / HTTP/1.1\nHost: tv:4000\n\n"
        val error = runCatching {
            PairingHttp.readRequest(ByteArrayInputStream(raw.toByteArray()), 0)
        }.exceptionOrNull() as PairingHttp.RequestException
        assertThat(error.status).isEqualTo("400 Bad Request")
    }

    @Test
    fun `responses include anti-cache frame and content policy headers`() {
        val text = String(PairingHttp.httpResponse("200 OK", "text/html", "ok"), Charsets.UTF_8)
        assertThat(text).contains("Cache-Control: no-store")
        assertThat(text).contains("Referrer-Policy: no-referrer")
        assertThat(text).contains("X-Frame-Options: DENY")
        assertThat(text).contains("Content-Security-Policy:")
        assertThat(text).contains("form-action 'self'")
    }
}
