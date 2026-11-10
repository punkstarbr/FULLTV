/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The fixed offline speech languages supported by the live-caption feature. */
internal enum class SpeechLanguage(
    val displayName: String,
    val nativeName: String,
    val modelName: String,
    val bcp47Tag: String,
) {
    ENGLISH(
        displayName = "English-US",
        nativeName = "English",
        modelName = "vosk-model-small-en-us-0.15",
        bcp47Tag = "en-US",
    ),
    SPANISH(
        displayName = "Español",
        nativeName = "Español",
        modelName = "vosk-model-small-es-0.42",
        bcp47Tag = "es-ES",
    ),
    FRENCH(
        displayName = "Français",
        nativeName = "Français",
        modelName = "vosk-model-small-fr-0.22",
        bcp47Tag = "fr-FR",
    ),
    GERMAN(
        displayName = "Deutsch",
        nativeName = "Deutsch",
        modelName = "vosk-model-small-de-0.15",
        bcp47Tag = "de-DE",
    ),
    ITALIAN(
        displayName = "Italiano",
        nativeName = "Italiano",
        modelName = "vosk-model-small-it-0.22",
        bcp47Tag = "it-IT",
    ),
    RUSSIAN(
        displayName = "Русский",
        nativeName = "Русский",
        modelName = "vosk-model-small-ru-0.22",
        bcp47Tag = "ru-RU",
    ),
    CHINESE(
        displayName = "中文",
        nativeName = "中文",
        modelName = "vosk-model-small-cn-0.22",
        bcp47Tag = "zh-CN",
    ),
    JAPANESE(
        displayName = "日本語",
        nativeName = "日本語",
        modelName = "vosk-model-small-ja-0.22",
        bcp47Tag = "ja-JP",
    ),
    DUTCH(
        displayName = "Nederlands",
        nativeName = "Nederlands",
        modelName = "vosk-model-small-nl-0.22",
        bcp47Tag = "nl-NL",
    ),
    TURKISH(
        displayName = "Türkçe",
        nativeName = "Türkçe",
        modelName = "vosk-model-small-tr-0.3",
        bcp47Tag = "tr-TR",
    ),
    VIETNAMESE(
        displayName = "Tiếng Việt",
        nativeName = "Tiếng Việt",
        modelName = "vosk-model-small-vn-0.4",
        bcp47Tag = "vi-VN",
    ),
    HINDI(
        displayName = "हिन्दी",
        nativeName = "हिन्दी",
        modelName = "vosk-model-small-hi-0.22",
        bcp47Tag = "hi-IN",
    ),
    PORTUGUESE(
        displayName = "Português",
        nativeName = "Português",
        modelName = "vosk-model-small-pt-0.3",
        bcp47Tag = "pt-BR",
    );

    /** ML Kit Translate accepts base language codes such as `en`, not regional tags such as `en-US`. */
    val translationLanguageCode: String
        get() = bcp47Tag.substringBefore('-')

    companion object {
        /** Maps selected Media3 audio-track metadata to a supported Vosk model, if provided. */
        fun fromAudioTrackLanguageTag(tag: String?): SpeechLanguage? {
            val code = tag
                ?.trim()
                ?.replace('_', '-')
                ?.substringBefore('-')
                ?.lowercase(Locale.ROOT)
                ?: return null
            return when (code) {
                "en", "eng" -> ENGLISH
                "es", "spa" -> SPANISH
                "fr", "fra", "fre" -> FRENCH
                "de", "deu", "ger" -> GERMAN
                "it", "ita" -> ITALIAN
                "ru", "rus" -> RUSSIAN
                "zh", "zho", "chi", "cmn" -> CHINESE
                "ja", "jpn" -> JAPANESE
                "nl", "nld", "dut" -> DUTCH
                "tr", "tur" -> TURKISH
                "vi", "vie" -> VIETNAMESE
                "hi", "hin" -> HINDI
                "pt", "por" -> PORTUGUESE
                else -> null
            }
        }
    }
}

/** Downloads, validates, and atomically installs at most one Vosk model. */
internal class VoskSpeechModelStore(
    context: Context,
    private val httpClient: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val installMutex = Mutex()

    /**
     * Ensures that [language]'s model is installed and returns its model directory.
     * The callback receives a percentage when the response length is known, otherwise null.
     */
    suspend fun ensureModel(
        language: SpeechLanguage,
        onProgress: (Int?) -> Unit = {},
    ): File = installMutex.withLock {
        withContext(Dispatchers.IO) {
            val modelsDirectory = modelsDirectory()
            removeTransientArtifacts(modelsDirectory)
            val target = File(modelsDirectory, language.modelName)

            if (isValidModelDirectory(target, modelsDirectory)) {
                removeOtherInstalledModels(modelsDirectory, target)
                return@withContext target
            }

            val part = File(modelsDirectory, ".${language.modelName}.zip.part")
            val staging = File(modelsDirectory, ".${language.modelName}.staging")
            deleteRecursively(part, modelsDirectory)
            deleteRecursively(staging, modelsDirectory)

            try {
                downloadToPart(language, part, onProgress)
                currentCoroutineContext().ensureActive()
                extractZip(part, staging)
                currentCoroutineContext().ensureActive()

                val extractedModel = File(staging, language.modelName)
                check(isValidModelDirectory(extractedModel, staging)) {
                    "Vosk archive does not contain the expected model directory."
                }
                installAtomically(extractedModel, target, modelsDirectory)
                onProgress(100)
                target
            } finally {
                // The installed directory has already been renamed out of staging. Failed or
                // cancelled downloads therefore leave no usable partial model behind.
                deleteRecursively(part, modelsDirectory)
                deleteRecursively(staging, modelsDirectory)
            }
        }
    }

    private fun modelsDirectory(): File {
        val directory = File(appContext.noBackupFilesDir, MODELS_DIRECTORY_NAME).canonicalFile
        check(directory.isDirectory || directory.mkdirs()) {
            "Could not create the Vosk model directory."
        }
        return directory
    }

    private suspend fun downloadToPart(
        language: SpeechLanguage,
        part: File,
        onProgress: (Int?) -> Unit,
    ) {
        val request = Request.Builder()
            .url(modelUrl(language))
            .build()

        suspendCancellableCoroutine<Unit> { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                throw IOException("Vosk model download failed: HTTP ${it.code}.")
                            }
                            val body = it.body ?: throw IOException("Vosk model download was empty.")
                            val declaredLength = body.contentLength()
                            if (declaredLength > MAX_COMPRESSED_BYTES) {
                                throw IOException("Vosk model ZIP exceeds the compressed-size limit.")
                            }

                            val parent = part.parentFile
                                ?: throw IOException("Vosk download file has no parent directory.")
                            if (!parent.isDirectory && !parent.mkdirs()) {
                                throw IOException("Could not create the Vosk download directory.")
                            }
                            var downloaded = 0L
                            val knownLength = declaredLength >= 0L
                            onProgress(if (knownLength) 0 else null)
                            body.byteStream().use { input ->
                                FileOutputStream(part).use { output ->
                                    val buffer = ByteArray(IO_BUFFER_BYTES)
                                    while (true) {
                                        if (!continuation.isActive) {
                                            call.cancel()
                                            throw IOException("Vosk model download was cancelled.")
                                        }
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        if (downloaded > MAX_COMPRESSED_BYTES - count.toLong()) {
                                            throw IOException(
                                                "Vosk model ZIP exceeds the compressed-size limit.",
                                            )
                                        }
                                        output.write(buffer, 0, count)
                                        downloaded += count
                                        if (knownLength && declaredLength > 0L) {
                                            onProgress(
                                                ((downloaded * 100L) / declaredLength)
                                                    .toInt()
                                                    .coerceIn(0, 100),
                                            )
                                        } else if (!knownLength) {
                                            onProgress(null)
                                        }
                                    }
                                    output.fd.sync()
                                }
                            }
                            if (knownLength && downloaded != declaredLength) {
                                throw IOException("Vosk model download was incomplete.")
                            }
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private suspend fun extractZip(part: File, staging: File) {
        check(staging.mkdirs() || staging.isDirectory) {
            "Could not create the Vosk staging directory."
        }
        val stagingCanonical = staging.canonicalFile
        var entryCount = 0
        var totalUncompressed = 0L

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(part))).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        throw IOException("Vosk model ZIP contains too many entries.")
                    }

                    val entryName = safeEntryName(entry.name, entry.isDirectory)
                    val output = File(staging, entryName)
                    val canonicalOutput = output.canonicalFile
                    check(canonicalOutput.isWithin(stagingCanonical)) {
                        "Vosk model ZIP entry escapes the staging directory."
                    }

                    val declaredSize = entry.size
                    if (declaredSize > MAX_UNCOMPRESSED_BYTES - totalUncompressed) {
                        throw IOException("Vosk model ZIP exceeds the uncompressed-size limit.")
                    }

                    if (entry.isDirectory) {
                        check(output.isDirectory || output.mkdirs()) {
                            "Could not create a Vosk model directory."
                        }
                    } else {
                        val parent = output.parentFile
                            ?: throw IOException("Vosk model ZIP entry has no parent directory.")
                        check(parent.isDirectory || parent.mkdirs()) {
                            "Could not create a Vosk model entry directory."
                        }
                        FileOutputStream(output).use { fileOutput ->
                            val buffer = ByteArray(IO_BUFFER_BYTES)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = zip.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                if (totalUncompressed > MAX_UNCOMPRESSED_BYTES - count.toLong()) {
                                    throw IOException(
                                        "Vosk model ZIP exceeds the uncompressed-size limit.",
                                    )
                                }
                                fileOutput.write(buffer, 0, count)
                                totalUncompressed += count
                            }
                            fileOutput.fd.sync()
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            deleteRecursively(staging, stagingCanonical)
            throw error
        }
    }

    private fun installAtomically(
        extractedModel: File,
        target: File,
        modelsDirectory: File,
    ) {
        if (target.exists() && !deleteRecursively(target, modelsDirectory)) {
            throw IOException("Could not replace the previous Vosk model.")
        }
        check(extractedModel.renameTo(target)) {
            "Could not atomically install the Vosk model."
        }
        removeOtherInstalledModels(modelsDirectory, target)
    }

    private fun removeTransientArtifacts(modelsDirectory: File) {
        modelsDirectory.listFiles().orEmpty()
            .filter { isTransientArtifact(it.name) }
            .forEach { deleteRecursively(it, modelsDirectory) }
    }

    private fun removeOtherInstalledModels(modelsDirectory: File, keep: File) {
        val keepPath = keep.absoluteFile.path
        modelsDirectory.listFiles().orEmpty()
            .filter { it.absoluteFile.path != keepPath && !isTransientArtifact(it.name) }
            .forEach { candidate ->
                check(deleteRecursively(candidate, modelsDirectory)) {
                    "Could not remove an old Vosk model."
                }
            }
    }

    private fun isValidModelDirectory(directory: File, containingDirectory: File): Boolean {
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        if (!canonicalDirectory.isWithin(containingDirectory.canonicalFile) ||
            !directory.isDirectory ||
            directory.absoluteFile.path != canonicalDirectory.path
        ) {
            return false
        }
        val acousticModel = File(directory, "am/final.mdl")
        val modelConfig = File(directory, "conf/model.conf")
        return acousticModel.isFile && modelConfig.isFile &&
            acousticModel.canonicalFile.isWithin(canonicalDirectory) &&
            modelConfig.canonicalFile.isWithin(canonicalDirectory)
    }

    private fun deleteRecursively(file: File, containingDirectory: File): Boolean {
        if (!file.exists()) return true
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val containingCanonical = containingDirectory.canonicalFile
        if (!canonical.isWithin(containingCanonical) ||
            file.absoluteFile.path != canonical.path
        ) {
            // Never follow a link while cleaning our private directory. delete() removes the
            // link itself when this branch is reached, not its target.
            return file.delete() || !file.exists()
        }
        if (file.isDirectory && file.listFiles().orEmpty().any {
                !deleteRecursively(it, containingCanonical)
            }
        ) {
            return false
        }
        return file.delete() || !file.exists()
    }

    private fun safeEntryName(rawName: String, directory: Boolean): String {
        check(rawName.isNotEmpty() && !rawName.contains('\\') && !rawName.contains('\u0000')) {
            "Vosk model ZIP contains an unsafe path."
        }
        if (rawName.startsWith('/') || rawName.startsWith("//") || DRIVE_PATH.matches(rawName)) {
            throw IOException("Vosk model ZIP contains an absolute path.")
        }
        val name = if (directory) rawName.trimEnd('/') else rawName
        check(name.isNotEmpty()) { "Vosk model ZIP contains an empty path." }
        check(name.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Vosk model ZIP contains a traversal path."
        }
        return name
    }

    private fun modelUrl(language: SpeechLanguage) =
        "https://alphacephei.com/vosk/models/${language.modelName}.zip".toHttpUrl()

    private fun File.isWithin(parent: File): Boolean {
        val parentPath = parent.path.trimEnd(File.separatorChar) + File.separator
        return path == parent.path || path.startsWith(parentPath)
    }

    internal companion object {
        const val MODELS_DIRECTORY_NAME = "live-caption-models"
        const val IO_BUFFER_BYTES = 64 * 1024
        const val MAX_COMPRESSED_BYTES = 100L * 1024 * 1024
        const val MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024
        const val MAX_ZIP_ENTRIES = 10_000
        val DRIVE_PATH = Regex("^[A-Za-z]:")

        fun isTransientArtifact(name: String): Boolean =
            name.startsWith('.') && (name.endsWith(".part") || name.endsWith(".staging"))
    }
}
