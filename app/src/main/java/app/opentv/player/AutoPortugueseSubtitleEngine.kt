/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class AutoSubtitlePhase {
    OFF,
    DOWNLOADING_SPEECH_MODEL,
    LOADING_SPEECH_MODEL,
    WAITING_FOR_AUDIO,
    TRANSCRIBING,
    DOWNLOADING_TRANSLATION_MODEL,
    READY,
    UNSUPPORTED_DEVICE,
    ERROR,
}

internal data class AutoPortugueseSubtitleState(
    val phase: AutoSubtitlePhase = AutoSubtitlePhase.OFF,
    val caption: String = "",
    val error: String? = null,
    val progressPercent: Int? = null,
)

/**
 * Optional on-device speech recognition and translation for the full-screen player. Stream PCM is
 * never sent to a network service: only model files are downloaded. The native Whisper AAR is
 * currently arm64-v8a only, so other ABIs fail closed with a visible status instead of crashing.
 */
internal class AutoPortugueseSubtitleEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val windows = Channel<PcmAudioWindow>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _state = MutableStateFlow(AutoPortugueseSubtitleState())
    val state: StateFlow<AutoPortugueseSubtitleState> = _state.asStateFlow()

    val audioBufferSink = LivePcmAudioCapture { window -> windows.trySend(window) }

    private val modelClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private var processingJob: Job? = null
    private var captionExpiryJob: Job? = null
    private var whisperModel: WhisperModel? = null
    private var languageIdentifier: com.google.mlkit.nl.languageid.LanguageIdentifier? = null
    private val translators = mutableMapOf<String, Translator>()
    private val downloadedTranslationModels = mutableSetOf<String>()
    private var closed = false
    private var lastCaptionKey = ""

    fun start() {
        if (processingJob != null || closed) return
        processingJob = workerScope.launch {
            val unsupported = unsupportedDeviceMessage()
            if (unsupported != null) {
                _state.value = AutoPortugueseSubtitleState(
                    phase = AutoSubtitlePhase.UNSUPPORTED_DEVICE,
                    error = unsupported,
                )
                return@launch
            }

            try {
                setPhase(AutoSubtitlePhase.DOWNLOADING_SPEECH_MODEL)
                val modelFile = ensureWhisperModel()
                setPhase(AutoSubtitlePhase.LOADING_SPEECH_MODEL)
                whisperModel = Whisper.loadModel(appContext, modelFile.absolutePath)
                languageIdentifier = LanguageIdentification.getClient(
                    LanguageIdentificationOptions.Builder()
                        .setConfidenceThreshold(0.2f)
                        .build(),
                )
                audioBufferSink.setEnabled(true)
                setPhase(AutoSubtitlePhase.WAITING_FOR_AUDIO)

                for (window in windows) {
                    currentCoroutineContext().ensureActive()
                    val model = whisperModel ?: break
                    val wavFile = writeWav(window.samples)
                    try {
                        setPhase(AutoSubtitlePhase.TRANSCRIBING)
                        val result = Whisper.transcribe(
                            model,
                            wavFile.absolutePath,
                            WhisperConfig(
                                language = "auto",
                                translate = false,
                                threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 2),
                                printTimestamps = true,
                            ),
                        )
                        val candidate = result.segments
                            .lastOrNull { window.sequence == 0 || it.startMs >= OVERLAP_SKIP_MS }
                            ?.text
                            ?.trim()
                            .orEmpty()
                            .ifBlank { result.text.trim() }
                        if (candidate.isBlank()) {
                            setPhase(AutoSubtitlePhase.WAITING_FOR_AUDIO)
                            continue
                        }

                        val translated = translateToPortuguese(candidate).trim()
                        if (translated.isNotBlank()) showCaption(translated)
                        else setPhase(AutoSubtitlePhase.WAITING_FOR_AUDIO)
                    } finally {
                        wavFile.delete()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                audioBufferSink.setEnabled(false)
                _state.value = AutoPortugueseSubtitleState(
                    phase = AutoSubtitlePhase.ERROR,
                    error = error.message?.takeIf(String::isNotBlank) ?: "Model or translation could not be loaded.",
                )
            }
        }
    }

    private fun unsupportedDeviceMessage(): String? {
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            return "Whisper captions require an arm64-v8a device."
        }
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if ((manager?.memoryClass ?: 0) < MIN_MEMORY_CLASS_MB) {
            return "Whisper captions need at least 512 MB of app memory."
        }
        return null
    }

    private suspend fun translateToPortuguese(text: String): String {
        val identifier = languageIdentifier ?: return text
        val languageTag = runCatching { identifier.identifyLanguage(text).awaitResult() }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?: return text
        if (languageTag == "und" || languageTag == "pt" || languageTag.startsWith("pt-")) return text

        val sourceLanguage = TranslateLanguage.fromLanguageTag(languageTag) ?: return text
        if (sourceLanguage == TranslateLanguage.PORTUGUESE) return text

        val translator = translators.getOrPut(sourceLanguage) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                    .build(),
            )
        }

        if (sourceLanguage !in downloadedTranslationModels) {
            setPhase(AutoSubtitlePhase.DOWNLOADING_TRANSLATION_MODEL)
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
            downloadedTranslationModels += sourceLanguage
        }
        return translator.translate(text).awaitResult()
    }

    private suspend fun ensureWhisperModel(): File = withContext(Dispatchers.IO) {
        val directory = File(appContext.filesDir, "ai-models/whisper").apply { mkdirs() }
        val model = File(directory, MODEL_FILE_NAME)
        if (model.length() == MODEL_SIZE_BYTES && sha256(model) == MODEL_SHA256) return@withContext model
        model.delete()

        val partial = File(directory, "$MODEL_FILE_NAME.part")
        partial.delete()
        val request = Request.Builder()
            .url(MODEL_URL)
            .header("User-Agent", "FullTV/0.1 Android")
            .build()
        val call = modelClient.newCall(request)
        val currentJob = currentCoroutineContext()[Job] ?: error("Model download job is unavailable.")
        val cancellation = currentJob.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) error("Whisper model download failed (HTTP ${response.code}).")
                val body = response.body ?: error("Whisper model download returned no data.")
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var nextProgressAt = PROGRESS_STEP_BYTES
                body.byteStream().use { input: InputStream ->
                    BufferedOutputStream(FileOutputStream(partial)).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloaded += count
                            if (downloaded >= nextProgressAt) {
                                val percent = (downloaded * 100 / MODEL_SIZE_BYTES).toInt().coerceIn(0, 99)
                                _state.value = AutoPortugueseSubtitleState(
                                    phase = AutoSubtitlePhase.DOWNLOADING_SPEECH_MODEL,
                                    progressPercent = percent,
                                )
                                nextProgressAt += PROGRESS_STEP_BYTES
                            }
                        }
                    }
                }
                if (downloaded != MODEL_SIZE_BYTES) {
                    error("Incomplete Whisper model download ($downloaded bytes).")
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(MODEL_SHA256, ignoreCase = true)) {
                    error("Whisper model checksum did not match.")
                }
                if (!partial.renameTo(model)) error("Could not save the Whisper model.")
            }
        } finally {
            cancellation.dispose()
            partial.delete()
        }
        model
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeWav(samples: FloatArray): File {
        val file = File(appContext.cacheDir, "fulltv-caption-${UUID.randomUUID()}.wav")
        val dataSize = samples.size * Short.SIZE_BYTES
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.writeAscii("RIFF")
            output.writeIntLe(36 + dataSize)
            output.writeAscii("WAVE")
            output.writeAscii("fmt ")
            output.writeIntLe(16)
            output.writeShortLe(1)
            output.writeShortLe(1)
            output.writeIntLe(TARGET_SAMPLE_RATE)
            output.writeIntLe(TARGET_SAMPLE_RATE * Short.SIZE_BYTES)
            output.writeShortLe(Short.SIZE_BYTES)
            output.writeShortLe(16)
            output.writeAscii("data")
            output.writeIntLe(dataSize)
            samples.forEach { sample ->
                val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                output.writeShortLe(pcm)
            }
        }
        return file
    }

    private fun showCaption(text: String) {
        val key = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        if (key == lastCaptionKey) {
            setPhase(AutoSubtitlePhase.WAITING_FOR_AUDIO)
            return
        }
        lastCaptionKey = key
        captionExpiryJob?.cancel()
        _state.value = AutoPortugueseSubtitleState(phase = AutoSubtitlePhase.READY, caption = text)
        captionExpiryJob = workerScope.launch {
            delay(CAPTION_HOLD_MILLIS)
            _state.update { current ->
                if (current.caption == text) current.copy(caption = "", phase = AutoSubtitlePhase.WAITING_FOR_AUDIO)
                else current
            }
        }
    }

    private fun setPhase(phase: AutoSubtitlePhase) {
        _state.update { it.copy(phase = phase, error = null, progressPercent = null) }
    }

    override fun close() {
        if (closed) return
        closed = true
        audioBufferSink.close()
        windows.close()
        processingJob?.cancel()
        captionExpiryJob?.cancel()
        languageIdentifier?.close()
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
        whisperModel?.let { runCatching { Whisper.releaseModel(it) } }
        whisperModel = null
        workerScope.cancel()
    }

    private companion object {
        const val MODEL_FILE_NAME = "ggml-tiny.bin"
        const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin?download=true"
        const val MODEL_SIZE_BYTES = 77_691_713L
        const val MODEL_SHA256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
        const val PROGRESS_STEP_BYTES = 1_048_576L
        const val MIN_MEMORY_CLASS_MB = 512
        const val TARGET_SAMPLE_RATE = 16_000
        const val OVERLAP_SKIP_MS = 1_800L
        const val CAPTION_HOLD_MILLIS = 4_500L
    }
}

private fun java.io.OutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))
private fun java.io.OutputStream.writeIntLe(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
    write((value ushr 16) and 0xff)
    write((value ushr 24) and 0xff)
}
private fun java.io.OutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel(CancellationException("ML Kit task cancelled"))
    }
}
