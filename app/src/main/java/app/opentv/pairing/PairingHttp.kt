/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Pure, testable HTTP/1.1 framing and response helpers for the local TLS servers.
 *
 * Requests are intentionally limited to GET/POST with Content-Length framing. Chunked requests,
 * ambiguous duplicate headers/parameters, oversized headers, and oversized bodies are rejected.
 */
object PairingHttp {

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    ) {
        val contentLength: Int
            get() = headers["content-length"]?.toIntOrNull() ?: 0
    }

    class RequestException(
        val status: String,
        message: String,
    ) : IOException(message)

    /** Parses a strict request line such as `GET /?t=abc HTTP/1.1`. */
    fun parseRequestLine(line: String): Triple<String, String, Map<String, String>>? {
        if (line.isBlank() || line.length > MAX_REQUEST_LINE_BYTES) return null
        val parts = line.split(' ')
        if (parts.size != 3 || parts.any(String::isBlank)) return null
        val method = parts[0].uppercase()
        if (method != "GET" && method != "POST") return null
        val target = parts[1]
        if (!target.startsWith('/') || target.startsWith("//") || target.contains('#') || target.contains('\\')) {
            return null
        }
        if (parts[2] != "HTTP/1.1") return null
        val path = target.substringBefore('?')
        if (path.isEmpty() || path.any { it.code < 0x21 || it.code > 0x7e }) return null
        val query = parseFormEncodedStrict(target.substringAfter('?', "")) ?: return null
        return Triple(method, path, query)
    }

    /** Reads one bounded HTTP/1.1 request. Returns null only when the peer closed before sending data. */
    fun readRequest(input: InputStream, maxBodyBytes: Int): Request? {
        require(maxBodyBytes >= 0)
        val stream = BufferedInputStream(input)
        var totalHeaderBytes = 0

        fun line(maxLineBytes: Int): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = stream.read()
                if (next < 0) {
                    if (bytes.size() == 0) return null
                    throw RequestException("400 Bad Request", "Truncated HTTP line")
                }
                totalHeaderBytes++
                if (totalHeaderBytes > MAX_HEADER_BYTES) {
                    throw RequestException("431 Request Header Fields Too Large", "Request headers too large")
                }
                when (next) {
                    '\r'.code -> {
                        val lf = stream.read()
                        if (lf != '\n'.code) throw RequestException("400 Bad Request", "Expected CRLF")
                        totalHeaderBytes++
                        if (totalHeaderBytes > MAX_HEADER_BYTES) {
                            throw RequestException("431 Request Header Fields Too Large", "Request headers too large")
                        }
                        val raw = bytes.toByteArray()
                        if (raw.any { (it.toInt() and 0xff) > 0x7f }) {
                            throw RequestException("400 Bad Request", "Non-ASCII HTTP framing")
                        }
                        return String(raw, Charsets.US_ASCII)
                    }
                    '\n'.code -> throw RequestException("400 Bad Request", "Expected CRLF")
                    else -> {
                        if (next < 0x20 && next != '\t'.code || next == 0x7f) {
                            throw RequestException("400 Bad Request", "Control character in HTTP framing")
                        }
                        bytes.write(next)
                        if (bytes.size() > maxLineBytes) {
                            throw RequestException("431 Request Header Fields Too Large", "HTTP line too large")
                        }
                    }
                }
            }
        }

        val requestLine = line(MAX_REQUEST_LINE_BYTES) ?: return null
        val parsedLine = parseRequestLine(requestLine)
            ?: throw RequestException("400 Bad Request", "Invalid HTTP request line")
        val headers = LinkedHashMap<String, String>()
        var headerCount = 0
        while (true) {
            val headerLine = line(MAX_HEADER_LINE_BYTES)
                ?: throw RequestException("400 Bad Request", "Incomplete HTTP headers")
            if (headerLine.isEmpty()) break
            headerCount++
            if (headerCount > MAX_HEADER_COUNT) {
                throw RequestException("431 Request Header Fields Too Large", "Too many HTTP headers")
            }
            val parsedHeader = parseHeader(headerLine)
                ?: throw RequestException("400 Bad Request", "Invalid HTTP header")
            if (headers.putIfAbsent(parsedHeader.first, parsedHeader.second) != null) {
                throw RequestException("400 Bad Request", "Duplicate HTTP header")
            }
        }
        if ("host" !in headers) throw RequestException("400 Bad Request", "Missing Host header")
        if ("transfer-encoding" in headers) {
            throw RequestException("400 Bad Request", "Transfer-Encoding is not supported")
        }
        if ("expect" in headers) throw RequestException("417 Expectation Failed", "Expect header is not supported")

        val contentLengthText = headers["content-length"]
        val contentLength = when {
            contentLengthText == null -> 0
            !contentLengthText.matches(Regex("[0-9]{1,10}")) ->
                throw RequestException("400 Bad Request", "Invalid Content-Length")
            else -> contentLengthText.toIntOrNull()
                ?: throw RequestException("413 Content Too Large", "Content-Length is too large")
        }
        if (contentLength > maxBodyBytes) {
            throw RequestException("413 Content Too Large", "Request body too large")
        }
        if (parsedLine.first == "POST" && contentLengthText == null) {
            throw RequestException("411 Length Required", "POST requires Content-Length")
        }
        if (parsedLine.first == "GET" && contentLength != 0) {
            throw RequestException("400 Bad Request", "GET requests cannot contain a body")
        }

        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = stream.read(bodyBytes, offset, contentLength - offset)
            if (count < 0) throw RequestException("400 Bad Request", "Truncated HTTP body")
            offset += count
        }
        val body = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bodyBytes))
                .toString()
        } catch (error: Exception) {
            throw RequestException("400 Bad Request", "Request body is not valid UTF-8")
        }
        return Request(
            method = parsedLine.first,
            path = parsedLine.second,
            query = parsedLine.third,
            headers = headers,
            body = body,
        )
    }

    /** Decodes form data tolerantly for display/tests; network authentication should use strict form parsing. */
    fun parseFormEncoded(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (key.isBlank()) return@mapNotNull null
            runCatching { decode(key) to decode(value) }.getOrNull()
        }.toMap()
    }

    /** Strict form decoder: malformed, empty-key, or duplicate fields are rejected. */
    fun parseFormEncodedStrict(raw: String): Map<String, String>? {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isBlank()) return null
            val key = pair.substringBefore('=')
            if (key.isBlank()) return null
            val decoded = runCatching { decode(key) to decode(pair.substringAfter('=', "")) }.getOrNull() ?: return null
            if (result.putIfAbsent(decoded.first, decoded.second) != null) return null
        }
        return result
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%20"), "UTF-8")

    /** Parses one HTTP header line into a lowercased name and its value. */
    fun parseHeader(line: String): Pair<String, String>? {
        if (line.isEmpty() || line.startsWith(' ') || line.startsWith('\t')) return null
        val index = line.indexOf(':')
        if (index <= 0) return null
        val name = line.substring(0, index)
        if (!name.matches(HEADER_NAME)) return null
        val value = line.substring(index + 1).trim(' ', '\t')
        if (value.any { it.code < 0x20 && it != '\t' || it.code == 0x7f }) return null
        return name.lowercase() to value
    }

    /** Constant-time comparison for the pairing token and one-time code. */
    fun secretsMatch(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
        return difference == 0
    }

    /** True for recognized private, link-local, CGNAT/Tailscale, and loopback addresses. */
    fun isPrivateAddress(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val address = host.substringAfterLast('%')
        if (address == "127.0.0.1" || address == "::1" || address == "0:0:0:0:0:0:0:1") return true
        val octets = address.split('.')
        if (octets.size != 4) {
            val lower = address.lowercase()
            return lower.startsWith("fd") || lower.startsWith("fc") || lower.startsWith("fe80")
        }
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        return when {
            numbers[0] == 10 -> true
            numbers[0] == 192 && numbers[1] == 168 -> true
            numbers[0] == 172 && numbers[1] in 16..31 -> true
            numbers[0] == 169 && numbers[1] == 254 -> true
            numbers[0] == 100 && numbers[1] in 64..127 -> true
            else -> false
        }
    }

    /** Browser mutations are accepted only from the exact HTTPS origin serving this short-lived UI. */
    fun isExpectedOrigin(origin: String?, expectedAuthority: String): Boolean =
        !origin.isNullOrBlank() && origin.trim().equals("https://$expectedAuthority", ignoreCase = true)

    fun httpResponse(
        status: String,
        contentType: String,
        body: String,
    ): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Cross-Origin-Resource-Policy: same-origin\r\n")
            append("Cross-Origin-Opener-Policy: same-origin\r\n")
            append("Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n")
            append("Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' https: data:; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'; object-src 'none'\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.US_ASCII) + bytes
    }

    private const val MAX_REQUEST_LINE_BYTES = 4096
    private const val MAX_HEADER_LINE_BYTES = 4096
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_HEADER_COUNT = 64
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
}
