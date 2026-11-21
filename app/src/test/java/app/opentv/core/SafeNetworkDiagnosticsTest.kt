package app.opentv.core

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeNetworkDiagnosticsTest {
    @Test
    fun transientNetworkFailuresAreRetryable() {
        assertTrue(SafeNetworkDiagnostics.isRetryableNetworkFailure(UnknownHostException("dns")))
        assertTrue(SafeNetworkDiagnostics.isRetryableNetworkFailure(SocketTimeoutException("timeout")))
        assertTrue(SafeNetworkDiagnostics.isRetryableNetworkFailure(IOException("io")))
    }

    @Test
    fun wrappedNetworkFailureIsRetryable() {
        val wrapper = IllegalStateException("wrapper", IOException("inner"))
        assertTrue(SafeNetworkDiagnostics.isRetryableNetworkFailure(wrapper))
    }

    @Test
    fun cancellationIsNeverTurnedIntoRetry() {
        val wrapper = IOException("cancelled", CancellationException("cancelled"))
        assertFalse(SafeNetworkDiagnostics.isRetryableNetworkFailure(wrapper))
        assertFalse(SafeNetworkDiagnostics.isRetryableNetworkFailure(CancellationException("cancelled")))
    }

    @Test
    fun tlsCertificateFailureIsNotRetried() {
        assertFalse(SafeNetworkDiagnostics.isRetryableNetworkFailure(SSLHandshakeException("tls")))
    }

    @Test
    fun onlyTransientHttpStatusesAreRetryable() {
        assertTrue(SafeNetworkDiagnostics.isRetryableHttpStatus(408))
        assertTrue(SafeNetworkDiagnostics.isRetryableHttpStatus(429))
        assertTrue(SafeNetworkDiagnostics.isRetryableHttpStatus(503))
        assertFalse(SafeNetworkDiagnostics.isRetryableHttpStatus(401))
        assertFalse(SafeNetworkDiagnostics.isRetryableHttpStatus(403))
        assertFalse(SafeNetworkDiagnostics.isRetryableHttpStatus(404))
    }

    @Test
    fun programmingFailureIsNotRetried() {
        assertFalse(SafeNetworkDiagnostics.isRetryableNetworkFailure(IllegalArgumentException("invalid")))
    }

    @Test
    fun formattedLogOmitsExceptionMessageAndSanitizesStage() {
        val failure = SocketTimeoutException("sensitive-provider-url-and-token")
        failure.stackTrace = arrayOf(StackTraceElement("app.opentv.Fake", "load", "Fake.kt", 42))

        val formatted = SafeNetworkDiagnostics.format("EPG feed / user URL", failure)

        assertTrue(formatted.contains("stage=epg_feed___user_url"))
        assertTrue(formatted.contains("SocketTimeoutException"))
        assertTrue(formatted.contains("app.opentv.Fake.load(Fake.kt:42)"))
        assertFalse(formatted.contains("sensitive-provider-url-and-token"))
    }
}
