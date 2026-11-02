/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.security

import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.Closeable
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Creates short-lived HTTPS listeners for local pairing, Web Manager and device-to-device sync.
 * The RSA private key is generated and retained by Android Keystore; it is never written into the
 * APK, preferences, backup files, or app-managed filesystem.
 *
 * The app uses a self-signed server certificate with the advertised LAN IP in an iPAddress SAN.
 * Browsers cannot automatically trust a per-install self-signed certificate, so browser-based
 * clients must compare the certificate SHA-256 shown on the TV with the certificate details in
 * their browser before proceeding. SyncClient uses the SPKI pin from the same device screen.
 */
object LanTlsServer {
    private const val KEY_ALIAS = "fulltv_lan_tls_rsa_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val CERT_VALIDITY_MILLIS = 365L * 24 * 60 * 60 * 1000
    const val SYNC_PIN_HEX_CHARS = 24 // 96-bit short authentication string, compared out of band.
    private val keyLock = Any()

    data class Listener(
        private val serverSocket: SSLServerSocket,
        val host: String,
        /** Full SHA-256 hash of the DER certificate, formatted for browser certificate details. */
        val certificateSha256: String,
        /** Full SHA-256 hash of SubjectPublicKeyInfo, lowercase hex. */
        val publicKeySha256: String,
        /** First 96 bits of the SPKI hash, entered on the receiving FullTV device. */
        val syncPin: String,
    ) : Closeable {
        val port: Int get() = serverSocket.localPort

        /** Accept one peer and complete TLS before the caller reads any application bytes. */
        fun acceptClient(readTimeoutMillis: Int): SSLSocket {
            val client = serverSocket.accept() as SSLSocket
            try {
                client.useClientMode = false
                client.enabledProtocols = arrayOf("TLSv1.2")
                client.enabledCipherSuites = serverSocket.enabledCipherSuites
                client.soTimeout = readTimeoutMillis
                client.tcpNoDelay = true
                return client
            } catch (t: Throwable) {
                runCatching { client.close() }
                throw t
            }
        }

        /** Complete the potentially slow handshake on a bounded request worker, not the accept loop. */
        fun completeHandshake(client: SSLSocket) {
            try {
                client.startHandshake()
            } catch (t: Throwable) {
                runCatching { client.close() }
                throw t
            }
        }

        override fun close() {
            serverSocket.close()
        }
    }

    /** Open a listener bound only to [host], never to every interface. Call from Dispatchers.IO. */
    fun open(host: String): Listener {
        val address = InetAddress.getByName(host)
        require(!address.isAnyLocalAddress && !address.isLoopbackAddress && address.address.size == 4) {
            "The local TLS listener requires the advertised private IPv4 address."
        }

        val keyPair = synchronized(keyLock) { loadOrCreateKeyPair() }
        val certificate = createServerCertificate(keyPair, address, System.currentTimeMillis())

        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null)
            // Android Keystore documents this operation as the way to replace the initially
            // generated certificate while retaining the non-exportable private key.
            setKeyEntry(KEY_ALIAS, keyPair.private, null, arrayOf(certificate))
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, null)

        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(keyManagerFactory.keyManagers, null, SecureRandom())
        val serverSocket = sslContext.serverSocketFactory.createServerSocket(
            0,
            16,
            address,
        ) as SSLServerSocket
        serverSocket.enabledProtocols = arrayOf("TLSv1.2")
        val secureSuites = secureCipherSuites(serverSocket.supportedCipherSuites)
        check(secureSuites.isNotEmpty()) { "No supported TLS 1.2 ECDHE AEAD cipher suites are available." }
        serverSocket.enabledCipherSuites = secureSuites
        serverSocket.needClientAuth = false

        val certificateHash = sha256Hex(certificate.encoded)
        val publicKeyHash = sha256Hex(certificate.publicKey.encoded)
        return Listener(
            serverSocket = serverSocket,
            host = requireNotNull(address.hostAddress),
            certificateSha256 = certificateHash,
            publicKeySha256 = publicKeyHash,
            syncPin = publicKeyHash.take(SYNC_PIN_HEX_CHARS),
        )
    }

    /**
     * Creates a strict pin trust manager for the local sync protocol. It accepts only the exact
     * self-signed server key confirmed on the sending FullTV screen. Hostname/IP-SAN verification
     * remains enabled separately in OkHttp; this is not a trust-all manager.
     */
    @SuppressLint("CustomX509TrustManager")
    fun pinnedTrustManager(pin: String): X509TrustManager {
        val expected = normalizeSyncPin(pin)
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("FullTV LAN sync does not accept client certificates.")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val serverChain = chain?.takeIf { it.size == 1 }
                    ?: throw CertificateException("Unexpected FullTV TLS certificate chain.")
                val certificate = serverChain[0]
                certificate.checkValidity()
                if (certificate.basicConstraints >= 0) {
                    throw CertificateException("A CA certificate cannot be used as the FullTV server leaf.")
                }
                val keyUsage = certificate.keyUsage
                if (keyUsage == null || keyUsage.isEmpty() || !keyUsage[0]) {
                    throw CertificateException("The FullTV certificate cannot authenticate a TLS server.")
                }
                val extendedKeyUsage = certificate.extendedKeyUsage.orEmpty()
                if (SERVER_AUTH_OID !in extendedKeyUsage) {
                    throw CertificateException("The FullTV certificate is missing serverAuth usage.")
                }
                val actual = sha256Hex(certificate.publicKey.encoded).take(SYNC_PIN_HEX_CHARS)
                if (!MessageDigest.isEqual(
                        expected.toByteArray(Charsets.US_ASCII),
                        actual.toByteArray(Charsets.US_ASCII),
                    )
                ) {
                    throw CertificateException("The TLS fingerprint does not match the sender screen.")
                }
                try {
                    certificate.verify(certificate.publicKey)
                } catch (error: Exception) {
                    throw CertificateException("The FullTV certificate is not self-signed by its key.", error)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    fun normalizeSyncPin(pin: String): String {
        val normalized = pin.filter(Char::isLetterOrDigit).lowercase()
        require(normalized.length == SYNC_PIN_HEX_CHARS && normalized.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Enter the 24-character TLS verification code shown on the sending device."
        }
        return normalized
    }

    /** Builds the exact certificate used by this session; kept pure for JVM certificate tests. */
    internal fun createServerCertificate(
        keyPair: KeyPair,
        address: InetAddress,
        nowMillis: Long,
    ): X509Certificate {
        require(address.address.size == 4) { "Only IPv4 listener certificates are supported." }
        require(!address.isAnyLocalAddress && !address.isLoopbackAddress) {
            "A server certificate cannot advertise a wildcard or loopback address."
        }

        val notBefore = Date(nowMillis - 5 * 60 * 1000L)
        val notAfter = Date(nowMillis + CERT_VALIDITY_MILLIS)
        val serial = java.math.BigInteger(159, SecureRandom()).let {
            if (it.signum() == 0) java.math.BigInteger.ONE else it
        }
        val subject = X500Name("CN=FullTV Local HTTPS")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )
        val extensionUtils = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
        )
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.iPAddress, address.hostAddress)),
        )
        builder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            extensionUtils.createSubjectKeyIdentifier(keyPair.public),
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        certificate.checkValidity(Date(nowMillis))
        certificate.verify(keyPair.public)
        require(certificate.publicKey.encoded.contentEquals(keyPair.public.encoded)) {
            "Generated TLS certificate does not match the Android Keystore key."
        }
        return certificate
    }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    internal fun formatCertificateFingerprint(hex: String): String =
        hex.chunked(2).joinToString(":") { it.uppercase() }

    internal fun secureCipherSuites(supported: Array<String>): Array<String> =
        supported.filter { suite ->
            suite.startsWith("TLS_ECDHE_RSA_WITH_") &&
                (suite.contains("_GCM_") || suite.contains("_CHACHA20_POLY1305_"))
        }.toTypedArray()

    private fun loadOrCreateKeyPair(): KeyPair {
        val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) {
            val privateKey = store.getKey(KEY_ALIAS, null) as? java.security.PrivateKey
            val certificate = store.getCertificate(KEY_ALIAS) as? X509Certificate
            if (privateKey != null && certificate != null) {
                return KeyPair(certificate.publicKey, privateKey)
            }
            runCatching { store.deleteEntry(KEY_ALIAS) }
        }

        val now = System.currentTimeMillis()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=FullTV Local HTTPS"))
                .setCertificateSerialNumber(java.math.BigInteger.ONE)
                .setCertificateNotBefore(Date(now - 24 * 60 * 60 * 1000L))
                .setCertificateNotAfter(Date(now + 10L * 365 * 24 * 60 * 60 * 1000))
                .build(),
        )
        return generator.generateKeyPair()
    }

    private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
}
