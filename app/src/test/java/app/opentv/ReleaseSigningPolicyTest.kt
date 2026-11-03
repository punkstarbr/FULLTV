package app.opentv

import app.opentv.update.ApkInstaller
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReleaseSigningPolicyTest {
    private fun digest(character: Char): String = character.toString().repeat(64)

    @Test
    fun releaseRequiresTheExplicitProductionCertificate() {
        val debugDigest = digest('d')
        val productionDigest = digest('a')

        assertThat(
            ApkInstaller.signingCertificatesMatch(
                installed = setOf(debugDigest),
                candidate = setOf(debugDigest),
                expectedReleaseSigner = productionDigest,
                debugBuild = false,
            )
        ).isFalse()
    }

    @Test
    fun releaseAcceptsOnlyTheConfiguredSingleSigner() {
        val productionDigest = digest('a')

        assertThat(
            ApkInstaller.signingCertificatesMatch(
                installed = setOf(productionDigest),
                candidate = setOf(productionDigest),
                expectedReleaseSigner = productionDigest,
                debugBuild = false,
            )
        ).isTrue()
    }

    @Test
    fun debugBuildKeepsItsIsolatedSameCertificatePath() {
        val debugDigest = digest('d')

        assertThat(
            ApkInstaller.signingCertificatesMatch(
                installed = setOf(debugDigest),
                candidate = setOf(debugDigest),
                expectedReleaseSigner = "",
                debugBuild = true,
            )
        ).isTrue()
    }
}
