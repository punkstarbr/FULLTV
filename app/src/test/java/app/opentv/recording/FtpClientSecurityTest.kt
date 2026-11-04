package app.opentv.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FtpClientSecurityTest {
    private fun config(
        host: String = "192.168.1.20",
        acknowledged: Boolean = true,
    ) = FtpConfig(
        host = host,
        port = 21,
        folder = "FullTV/Recordings",
        username = "fulltv-user",
        password = "secret-not-in-locator",
        plaintextAcknowledged = acknowledged,
    )

    @Test
    fun `accepts only canonical RFC1918 IPv4 unicast addresses`() {
        listOf("10.0.0.1", "172.16.4.8", "172.31.255.254", "192.168.1.20").forEach {
            assertThat(FtpClient.isPrivateRfc1918Ipv4(it)).isTrue()
        }
        listOf(
            "127.0.0.1",
            "169.254.1.1",
            "100.64.0.1",
            "8.8.8.8",
            "224.0.0.1",
            "192.168.1.255",
            "192.168.001.20",
            "nas.local",
            "::1",
        ).forEach { address ->
            assertThat(FtpClient.isPrivateRfc1918Ipv4(address)).isFalse()
        }
    }

    @Test
    fun `requires explicit plaintext acknowledgement before any FTP operation`() {
        val error = FtpClient.validationError(config(acknowledged = false))
        assertThat(error).contains("Confirm")
    }

    @Test
    fun `locator contains only local host and relative recording path`() {
        val locator = FtpClient.locator(config(), "movie.ts")
        assertThat(locator.startsWith("ftp://192.168.1.20:21/FullTV/Recordings/movie-")).isTrue()
        assertThat(locator.endsWith(".ts")).isTrue()
        assertThat(locator).doesNotContain("fulltv-user")
        assertThat(locator).doesNotContain("secret-not-in-locator")
        assertThat(FtpClient.validateLocator(config(), locator).remotePath.matches(
            Regex("FullTV/Recordings/movie-[0-9a-f]{32}\\.ts"),
        )).isTrue()
    }

    @Test
    fun `blank remote folder targets the FTP account root`() {
        val cfg = config().copy(folder = "")
        assertThat(FtpClient.isValidConfig(cfg)).isTrue()
        val locator = FtpClient.locator(cfg, "root.ts")
        assertThat(locator.startsWith("ftp://192.168.1.20:21/root-")).isTrue()
        assertThat(FtpClient.validateLocator(cfg, locator).remotePath.matches(
            Regex("root-[0-9a-f]{32}\\.ts"),
        )).isTrue()
    }

    @Test
    fun `locator percent encodes and round trips utf8 path segments`() {
        val cfg = config().copy(folder = "Mídia/Gravações")
        val locator = FtpClient.locator(cfg, "episódio 1#.ts")
        assertThat(locator.contains("%C3%AD")).isTrue()
        assertThat(locator.contains("%20")).isTrue()
        assertThat(locator.contains("%23")).isTrue()
        val remoteFilename = FtpClient.validateLocator(cfg, locator).remotePath.substringAfterLast('/')
        assertThat(remoteFilename.matches(Regex("episódio 1#-[0-9a-f]{32}\\.ts"))).isTrue()
    }

    @Test
    fun `rejects host mismatch userinfo query and path traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            FtpClient.validateLocator(config(), "ftp://192.168.1.21:21/FullTV/Recordings/movie.ts")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FtpClient.validateLocator(config(), "ftp://user:pass@192.168.1.20:21/FullTV/Recordings/movie.ts")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FtpClient.validateLocator(config(), "ftp://192.168.1.20:21/FullTV/Recordings/%2e%2e/secret.ts")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FtpClient.validateLocator(config(), "ftp://192.168.1.20:21/FullTV/Recordings/movie.ts?download=1")
        }
    }

    @Test
    fun `rejects hostnames and public IPv4 even with acknowledgement`() {
        assertThat(FtpClient.validationError(config(host = "nas.local"))).isNotNull()
        assertThat(FtpClient.validationError(config(host = "203.0.113.42"))).isNotNull()
    }

    @Test
    fun `parses a private FTP URL pasted into the host field`() {
        assertThat(FtpClient.parseEndpointInput("ftp://192.168.100.1:2121/USB/My%20Recordings"))
            .isEqualTo(FtpEndpointInput("192.168.100.1", 2121, "USB/My Recordings"))
        assertThat(FtpClient.parseEndpointInput("192.168.100.1:21/USB"))
            .isEqualTo(FtpEndpointInput("192.168.100.1", 21, "USB"))
        assertThat(FtpClient.parseEndpointInput("ftp://192.168.100.1/"))
            .isEqualTo(FtpEndpointInput("192.168.100.1", null, ""))
        assertThat(FtpClient.parseEndpointInput("ftp://192.168.100.1"))
            .isEqualTo(FtpEndpointInput("192.168.100.1", null, ""))
    }

    @Test
    fun `a bare private IP leaves the separate port and folder inputs unchanged`() {
        assertThat(FtpClient.parseEndpointInput("192.168.100.1")).isNull()
    }

    @Test
    fun `rejects public or credential-bearing FTP URLs and traversal`() {
        listOf(
            "ftp://203.0.113.10:21/recordings",
            "ftp://user:password@192.168.1.20/recordings",
            "ftp://192.168.1.20/../private",
            "ftp://192.168.1.20/recordings?download=1",
            "ftp://router.local/recordings",
        ).forEach { value -> assertThat(FtpClient.parseEndpointInput(value)).isNull() }
    }
}
