package app.opentv

import app.opentv.data.remote.GitHubAsset
import app.opentv.data.remote.GitHubRelease
import app.opentv.update.UpdateChecker
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GitHubReleaseTest {
    private val release = GitHubRelease(
        tagName = "v0.11.22",
        assets = listOf(
            GitHubAsset(name = "FullTV-0.11.22-universal.apk"),
            GitHubAsset(name = "FullTV-0.11.22-debug.apk"),
            GitHubAsset(name = "unexpected-app.apk"),
        ),
    )

    @Test
    fun prefersDebugAssetForDebugInstall() {
        assertThat(release.apkAsset(preferDebug = true)?.name)
            .isEqualTo("FullTV-0.11.22-debug.apk")
    }

    @Test
    fun prefersReleaseAssetForReleaseInstall() {
        assertThat(release.apkAsset(preferDebug = false)?.name)
            .isEqualTo("FullTV-0.11.22-universal.apk")
    }

    @Test
    fun refusesAnArbitraryApkOrMalformedVersion() {
        val onlyUntrusted = GitHubRelease(
            tagName = "v0.11.22",
            assets = listOf(GitHubAsset(name = "unexpected-app.apk")),
        )

        assertThat(onlyUntrusted.apkAsset()).isNull()
        assertThat(release.apkAsset(releaseTag = "latest")).isNull()
    }

    @Test
    fun v01142UsesTheCanonicalUniversalAssetAndUpdatesV01141() {
        val candidate = GitHubRelease(
            tagName = "v0.11.42",
            assets = listOf(GitHubAsset(name = "FullTV-0.11.42-universal.apk")),
        )

        assertThat(candidate.apkAsset(preferDebug = false)?.name)
            .isEqualTo("FullTV-0.11.42-universal.apk")
        assertThat(UpdateChecker.isNewer(candidate.tagName, "0.11.41")).isTrue()
    }
}
