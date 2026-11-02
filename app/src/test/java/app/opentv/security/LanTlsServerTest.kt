/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.security

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.util.Date
import org.junit.Test

class LanTlsServerTest {

    @Test
    fun `server certificate is self signed and scoped to the advertised IP`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val address = InetAddress.getByName("192.168.100.25")
        val now = System.currentTimeMillis()
        val certificate = LanTlsServer.createServerCertificate(keyPair, address, now)

        certificate.checkValidity(Date(now))
        certificate.verify(keyPair.public)
        assertThat(certificate.basicConstraints).isLessThan(0)
        assertThat(certificate.extendedKeyUsage).contains("1.3.6.1.5.5.7.3.1")
        assertThat(certificate.subjectAlternativeNames.orEmpty().any { san ->
            san.size >= 2 && san[0] == 7 && san[1] == address.hostAddress
        }).isTrue()
    }

    @Test
    fun `sync pin accepts only a single self signed server certificate with the confirmed SPKI`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val address = InetAddress.getByName("10.0.0.12")
        val certificate = LanTlsServer.createServerCertificate(keyPair, address, System.currentTimeMillis())
        val correctPin = LanTlsServer.sha256Hex(certificate.publicKey.encoded).take(LanTlsServer.SYNC_PIN_HEX_CHARS)
        val manager = LanTlsServer.pinnedTrustManager(correctPin.uppercase().chunked(4).joinToString(" "))

        manager.checkServerTrusted(arrayOf(certificate), "RSA")

        val wrongPin = (if (correctPin.first() == '0') "1" else "0") + correctPin.drop(1)
        val failure = runCatching {
            LanTlsServer.pinnedTrustManager(wrongPin).checkServerTrusted(arrayOf(certificate), "RSA")
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(java.security.cert.CertificateException::class.java)
    }

    @Test
    fun `sync pin rejects malformed codes and unexpected certificate chains`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = LanTlsServer.createServerCertificate(
            keyPair,
            InetAddress.getByName("192.168.1.30"),
            System.currentTimeMillis(),
        )
        val pin = LanTlsServer.sha256Hex(certificate.publicKey.encoded).take(LanTlsServer.SYNC_PIN_HEX_CHARS)
        val malformed = runCatching { LanTlsServer.normalizeSyncPin("not-a-valid-pin") }.exceptionOrNull()
        val unexpectedChain = runCatching {
            LanTlsServer.pinnedTrustManager(pin).checkServerTrusted(arrayOf(certificate, certificate), "RSA")
        }.exceptionOrNull()

        assertThat(malformed).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(unexpectedChain).isInstanceOf(java.security.cert.CertificateException::class.java)
    }

    @Test
    fun `server enables only ECDHE RSA AEAD cipher suites`() {
        val enabled = LanTlsServer.secureCipherSuites(
            arrayOf(
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            ),
        )

        assertThat(enabled.asList()).containsExactly(
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        ).inOrder()
    }
}
