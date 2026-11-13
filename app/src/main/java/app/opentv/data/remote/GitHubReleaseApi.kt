/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The slice of GitHub's `releases/latest` response we care about.
 *
 * Every field defaults, so a shape change on GitHub's side (a renamed or dropped key) leaves
 * us with an empty value rather than a parse exception that would break launch. `ignoreUnknownKeys`
 * on the decoder handles the ninety other fields we do not read.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubAsset> = emptyList(),
) {
    /**
     * Selects the APK matching the installed application id. Release builds prefer the first
     * supported ABI in device order and fall back to the canonical universal bridge asset. Debug
     * builds have the `.debug` suffix and cannot be upgraded to the release package id (or vice versa).
     */
    fun apkAsset(
        releaseTag: String = this.tagName,
        preferDebug: Boolean = false,
        supportedAbis: List<String> = emptyList(),
    ): GitHubAsset? {
        val version = releaseTag.trim().removePrefix("v").removePrefix("V")
        if (!version.matches(Regex("\\d+(?:\\.\\d+){1,3}"))) return null
        if (preferDebug) {
            val debugName = "FullTV-$version-debug.apk"
            return assets.singleOrNull { it.name.equals(debugName, ignoreCase = true) }
        }

        for (abi in supportedAbis) {
            if (abi !in SUPPORTED_APK_ABIS) continue
            val abiName = "FullTV-$version-$abi.apk"
            val matches = assets.filter { it.name.equals(abiName, ignoreCase = true) }
            if (matches.size > 1) return null
            if (matches.size == 1) return matches.single()
        }

        val universalName = "FullTV-$version-universal.apk"
        return assets.singleOrNull { it.name.equals(universalName, ignoreCase = true) }
    }

    private companion object {
        val SUPPORTED_APK_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
    }
}

@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
    /** GitHub REST API SHA-256 digest, e.g. `sha256:abcdef…`. */
    val digest: String? = null,
)
