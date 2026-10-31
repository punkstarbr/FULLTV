package app.opentv

import app.opentv.data.remote.GitHubAsset
import app.opentv.data.remote.GitHubRelease
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitHubReleaseTest {
    private val release = GitHubRelease(
        assets = listOf(
            GitHubAsset(name = "fulltv-v0.11.11.apk"),
            GitHubAsset(name = "fulltv-v0.11.11-debug.apk"),
        ),
    )

    @Test
    fun prefersDebugAssetForDebugInstall() {
        assertThat(release.apkAsset(preferDebug = true)?.name)
            .isEqualTo("fulltv-v0.11.11-debug.apk")
    }

    @Test
    fun prefersReleaseAssetForReleaseInstall() {
        assertThat(release.apkAsset(preferDebug = false)?.name)
            .isEqualTo("fulltv-v0.11.11.apk")
    }
}
