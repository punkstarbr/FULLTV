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

    @Test
    fun `port scan targets only other usable hosts in the local private block`() {
        val targets = FtpServerDiscovery.privateIpv4ScanHosts(address(192, 168, 100, 20), 24)

        assertThat(targets).hasSize(253)
        assertThat(targets).contains("192.168.100.1")
        assertThat(targets).contains("192.168.100.254")
        assertThat(targets).doesNotContain("192.168.100.0")
        assertThat(targets).doesNotContain("192.168.100.20")
        assertThat(targets).doesNotContain("192.168.100.255")
        assertThat(targets.all(FtpClient::isPrivateRfc1918Ipv4)).isTrue()
    }

    @Test
    fun `large subnets are capped to the current block and smaller subnets are respected`() {
        val capped = FtpServerDiscovery.privateIpv4ScanHosts(address(10, 1, 44, 7), 16)
        assertThat(capped).hasSize(253)
        assertThat(capped.first()).startsWith("10.1.44.")
        assertThat(capped.last()).startsWith("10.1.44.")

        val smaller = FtpServerDiscovery.privateIpv4ScanHosts(address(192, 168, 1, 200), 25)
        assertThat(smaller).hasSize(125)
        assertThat(smaller).contains("192.168.1.129")
        assertThat(smaller).contains("192.168.1.254")
        assertThat(smaller).doesNotContain("192.168.1.127")
        assertThat(smaller).doesNotContain("192.168.1.255")
    }

    @Test
    fun `port scan refuses non-private interfaces and non-FTP banners`() {
        assertThat(FtpServerDiscovery.privateIpv4ScanHosts(address(8, 8, 8, 8), 24)).isEmpty()
        assertThat(FtpServerDiscovery.privateIpv4ScanHosts(address(192, 168, 1, 5), 32)).isEmpty()
        assertThat(FtpServerDiscovery.isFtpWelcomeBanner("220 FTP server ready")).isTrue()
        assertThat(FtpServerDiscovery.isFtpWelcomeBanner("220-Service ready")).isTrue()
        assertThat(FtpServerDiscovery.isFtpWelcomeBanner("2200 not FTP")).isFalse()
        assertThat(FtpServerDiscovery.isFtpWelcomeBanner("200 OK")).isFalse()
        assertThat(FtpServerDiscovery.isFtpWelcomeBanner(null)).isFalse()
    }

    private fun address(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
}
