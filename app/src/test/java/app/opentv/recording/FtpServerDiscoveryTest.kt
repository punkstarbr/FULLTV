package app.opentv.recording

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import org.junit.Test

class FtpServerDiscoveryTest {
    @Test
    fun `advertised RFC1918 IPv4 endpoint is returned`() {
        val candidate = FtpServerDiscovery.candidateForResolvedHost(
            serviceName = "Roteador FTP",
            address = address(192, 168, 100, 1),
            port = 2121,
        )

        assertThat(candidate).isEqualTo(FtpServerCandidate("Roteador FTP", "192.168.100.1", 2121))
    }

    @Test
    fun `public IPv4 endpoints are not offered`() {
        assertThat(
            FtpServerDiscovery.candidateForResolvedHost(
                serviceName = "External server",
                address = address(8, 8, 8, 8),
                port = 21,
            ),
        ).isNull()
    }

    @Test
    fun `IPv6 and invalid ports are not offered`() {
        val ipv6 = InetAddress.getByAddress(ByteArray(16) { 0 })
        assertThat(FtpServerDiscovery.candidateForResolvedHost("v6", ipv6, 21)).isNull()
        assertThat(FtpServerDiscovery.candidateForResolvedHost("bad port", address(10, 0, 0, 5), 0)).isNull()
        assertThat(FtpServerDiscovery.candidateForResolvedHost("bad port", address(10, 0, 0, 5), 65_536)).isNull()
    }

    @Test
    fun `missing service name falls back to the numeric address`() {
        val candidate = FtpServerDiscovery.candidateForResolvedHost(
            serviceName = "\n",
            address = address(10, 0, 0, 5),
            port = 21,
        )

        assertThat(candidate?.name).isEqualTo("10.0.0.5")
    }

    private fun address(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
}
