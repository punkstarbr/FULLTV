/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import app.opentv.BuildConfig
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Downloads an update APK and hands it to the system installer only after validating the asset
 * digest, package id, monotonic version and signing certificate against the installed app.
 * Android still presents the final install confirmation; FullTV never installs silently.
 */
class ApkInstaller(private val http: OkHttpClient) {

    class InstallPermissionRequiredException : Exception()

    /** Progress as a 0f..1f fraction. */
    fun interface Progress {
        fun onProgress(fraction: Float)
    }

    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        expectedBytes: Long,
        expectedSha256: String,
        expectedVersionName: String,
        progress: Progress,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            throw InstallPermissionRequiredException()
        }
        val apk = download(context, url, expectedBytes, expectedSha256, progress)
        validatePackage(context, apk, expectedVersionName)
        launchInstaller(context, apk)
        return true
    }

    private suspend fun download(
        context: Context,
        url: String,
        expectedBytes: Long,
        expectedSha256: String,
        progress: Progress,
    ): File = withContext(Dispatchers.IO) {
        require(expectedBytes in 1L..MAX_APK_BYTES) { "Update APK has an invalid size." }
        val expectedDigest = expectedSha256.lowercase()
        require(expectedDigest.matches(SHA256_HEX)) { "Update APK digest is missing or invalid." }
        val parsedUrl = url.toHttpUrlOrNull() ?: error("Update download URL is invalid.")
        require(parsedUrl.isHttps && parsedUrl.host == "github.com" &&
            parsedUrl.encodedPath.contains("/releases/download/") &&
            parsedUrl.query == null && parsedUrl.fragment == null
        ) { "Update must be downloaded from the project's HTTPS GitHub release." }

        val dir = File(context.cacheDir, "updates").apply { check(isDirectory || mkdirs()) }
        val part = File(dir, "fulltv-update.apk.part")
        val out = File(dir, "fulltv-update.apk")
        part.delete()
        out.delete()
        try {
            val request = Request.Builder().url(parsedUrl).header("User-Agent", "FullTV").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Update download failed: HTTP ${response.code}.")
                if (!response.request.url.isHttps) error("Update download attempted an insecure redirect.")
                val body = response.body ?: error("Update download was empty.")
                if (body.contentLength() >= 0 && body.contentLength() != expectedBytes) {
                    error("Update APK size does not match the release metadata.")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                body.byteStream().use { input ->
                    FileOutputStream(part).use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > expectedBytes || written > MAX_APK_BYTES) {
                                error("Update APK exceeds the declared size.")
                            }
                            digest.update(buffer, 0, count)
                            sink.write(buffer, 0, count)
                            progress.onProgress(written.toFloat() / expectedBytes)
                        }
                        sink.fd.sync()
                    }
                }
                if (written != expectedBytes) error("Update APK download was incomplete.")
                if (digest.digest().toHex() != expectedDigest) error("Update APK SHA-256 verification failed.")
            }
            if (!part.renameTo(out)) error("Could not finalize the verified update APK.")
            out
        } catch (error: Exception) {
            part.delete()
            out.delete()
            throw error
        }
    }

    private fun validatePackage(context: Context, apk: File, expectedVersionName: String) {
        val packageManager = context.packageManager
        val installed = packageInfo(packageManager, context.packageName)
        val candidate = archivePackageInfo(packageManager, apk.absolutePath)
            ?: error("Downloaded file is not a valid Android package.")
        require(candidate.packageName == context.packageName) {
            "The update belongs to a different Android application."
        }
        require(candidate.versionName == expectedVersionName) {
            "Update package version does not match the published release."
        }
        require(versionCode(candidate) > versionCode(installed)) {
            "Update package is not newer than the installed application."
        }
        val currentSigners = signerDigests(installed)
        val candidateSigners = signerDigests(candidate)
        require(
            signingCertificatesMatch(
                installed = currentSigners,
                candidate = candidateSigners,
                expectedReleaseSigner = BuildConfig.RELEASE_SIGNING_CERT_SHA256,
                debugBuild = BuildConfig.DEBUG,
            )
        ) {
            "Update signing certificate does not match this FullTV installation."
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return packageManager.getPackageInfo(packageName, flags)
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(packageManager: PackageManager, path: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return packageManager.getPackageArchiveInfo(path, flags)
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            packageInfo.signatures
        } ?: return emptySet()
        return signatures.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
    }

    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }

    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        context.startActivity(intent)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    internal companion object {
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        val SHA256_HEX = Regex("[0-9a-f]{64}")

        /** Release updates may not inherit an Android debug certificate as their trust anchor. */
        fun signingCertificatesMatch(
            installed: Set<String>,
            candidate: Set<String>,
            expectedReleaseSigner: String,
            debugBuild: Boolean,
        ): Boolean {
            if (debugBuild) return installed.isNotEmpty() && candidate == installed
            return expectedReleaseSigner.matches(SHA256_HEX) &&
                installed == setOf(expectedReleaseSigner) &&
                candidate == installed
        }
    }
}
