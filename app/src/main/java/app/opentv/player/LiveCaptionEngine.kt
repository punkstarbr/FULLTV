/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class LiveCaptionPhase {
    OFF,
    PREPARING_SPEECH_MODEL,
    DOWNLOADING_SPEECH_MODEL,
    LOADING_SPEECH_MODEL,
    DOWNLOADING_TRANSLATION_MODEL,
    WAITING_FOR_AUDIO,
    LISTENING,
    TRANSCRIBING,
    TRANSLATING,
    ERROR,
}

internal enum class LiveCaptionError {
    LOW_MEMORY,
    SPEECH_MODEL,
    TRANSLATION_MODEL,
    RECOGNITION,
    LANGUAGE_METADATA_MISSING,
}

internal data class LiveCaptionState(
    val phase: LiveCaptionPhase = LiveCaptionPhase.OFF,
    val language: SpeechLanguage = SpeechLanguage.ENGLISH,
    val progressPercent: Int? = null,
    val sourceText: String = "",
    val caption: String = "",
    val captionId: Long = 0L,
    val automaticLanguageSelection: Boolean = false,
    val error: LiveCaptionError? = null,
) {
    val isActive: Boolean
        get() = phase != LiveCaptionPhase.OFF && phase != LiveCaptionPhase.ERROR
}

/**
 * Optional offline speech recognition for the fullscreen player. Audio comes only from Media3's
 * decoded PCM tap; this class does not request microphone access, open a second stream, or upload
 * audio/transcripts. Vosk and ML Kit translation models are initialized only after explicit start.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class LiveCaptionEngine(
    context: Context,
    httpClient: OkHttpClient,
    private val audioTap: LiveAudioTap,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelStore = VoskSpeechModelStore(appContext, httpClient)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val _state = MutableStateFlow(LiveCaptionState())
    val state: StateFlow<LiveCaptionState> = _state.asStateFlow()

    @Volatile private var closed = false
    private var sessionJob: Job? = null

    @Synchronized
    fun start(language: SpeechLanguage) = startInternal(language, automatic = false)

    /** Starts with the selected audio track's declared language; it does not analyze raw speech. */
    @Synchronized
    fun startAutomatically(language: SpeechLanguage?) {
        if (closed) return
        if (language == null) {
            stop()
            _state.value = LiveCaptionState(
                phase = LiveCaptionPhase.ERROR,
                error = LiveCaptionError.LANGUAGE_METADATA_MISSING,
            )
            return
        }
        startInternal(language, automatic = true)
    }

    @Synchronized
    private fun startInternal(language: SpeechLanguage, automatic: Boolean) {
        if (closed) return
        if (_state.value.isActive && _state.value.language == language &&
            _state.value.automaticLanguageSelection == automatic
        ) return

        sessionJob?.cancel()
        audioTap.stopCapture()
        val runId = generation.incrementAndGet()
        _state.value = LiveCaptionState(
            phase = LiveCaptionPhase.PREPARING_SPEECH_MODEL,
            language = language,
            automaticLanguageSelection = automatic,
        )
        sessionJob = workerScope.launch {
            sessionMutex.withLock { runSession(runId, language) }
        }
    }

    @Synchronized
    fun stop() {
        if (closed) return
        generation.incrementAndGet()
        sessionJob?.cancel()
        sessionJob = null
        audioTap.stopCapture()
        _state.value = LiveCaptionState()
    }

    private suspend fun runSession(runId: Long, language: SpeechLanguage) {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var translator: Translator? = null
        var translationJob: Job? = null
        val translations = Channel<String>(
            capacity = MAX_PENDING_TRANSLATIONS,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        try {
            if (isLowMemoryDevice()) {
                setError(runId, language, LiveCaptionError.LOW_MEMORY)
                return
            }

            setPhase(runId, language, LiveCaptionPhase.PREPARING_SPEECH_MODEL)
            val modelDirectory = try {
                modelStore.ensureModel(language) { percent ->
                    updateForRun(runId) {
                        it.copy(
                            phase = LiveCaptionPhase.DOWNLOADING_SPEECH_MODEL,
                            progressPercent = percent,
                            error = null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setError(runId, language, LiveCaptionError.SPEECH_MODEL)
                return
            }

            setPhase(runId, language, LiveCaptionPhase.LOADING_SPEECH_MODEL)
            model = Model(modelDirectory.absolutePath)
            recognizer = Recognizer(model, TARGET_SAMPLE_RATE_HZ.toFloat())

            val sourceLanguage = TranslateLanguage.fromLanguageTag(language.translationLanguageCode)
            if (sourceLanguage == null) {
                setError(runId, language, LiveCaptionError.TRANSLATION_MODEL)
                return
            }
            var preparedTranslator: Translator? = null
            if (sourceLanguage != TranslateLanguage.PORTUGUESE) {
                setPhase(runId, language, LiveCaptionPhase.DOWNLOADING_TRANSLATION_MODEL)
                val newTranslator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage)
                        .setTargetLanguage(TranslateLanguage.PORTUGUESE)
                        .build(),
                )
                translator = newTranslator
                try {
                    newTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitCaptionTask()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    setError(runId, language, LiveCaptionError.TRANSLATION_MODEL)
                    return
                }
                preparedTranslator = newTranslator
            }

            val activeRecognizer = requireNotNull(recognizer)
            val activeTranslator = preparedTranslator
            translationJob = workerScope.launch {
                for (sourceText in translations) {
                    if (runId != generation.get() || closed) continue
                    try {
                        setPhase(runId, language, LiveCaptionPhase.TRANSLATING)
                        val translated = if (activeTranslator == null) {
                            sourceText
                        } else {
                            activeTranslator.translate(sourceText).awaitCaptionTask()
                        }.trim().ifBlank { sourceText }
                        updateForRun(runId) { previous ->
                            previous.copy(
                                phase = LiveCaptionPhase.LISTENING,
                                sourceText = sourceText,
                                caption = translated.take(MAX_CAPTION_CHARS),
                                captionId = previous.captionId + 1,
                                progressPercent = null,
                                error = null,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Preserve the recognized words on-device if translation fails.
                        updateForRun(runId) { previous ->
                            previous.copy(
                                phase = LiveCaptionPhase.LISTENING,
                                sourceText = sourceText,
                                caption = sourceText.take(MAX_CAPTION_CHARS),
                                captionId = previous.captionId + 1,
                                error = LiveCaptionError.TRANSLATION_MODEL,
                            )
                        }
                    }
                }
            }

            currentCoroutineContextEnsureActive()
            audioTap.startCapture()
            setPhase(runId, language, LiveCaptionPhase.WAITING_FOR_AUDIO)
            val converter = Pcm16kStreamConverter()
            val speechGate = Pcm16kSpeechGate()
            var lastPartialText = ""
            var lastPartialUpdateMillis = 0L

            while (runId == generation.get() && !closed) {
                val packet = audioTap.receive()
                if (_state.value.phase == LiveCaptionPhase.WAITING_FOR_AUDIO) {
                    setPhase(runId, language, LiveCaptionPhase.LISTENING)
                }
                val pcm16Bytes = when (packet.encoding) {
                    C.ENCODING_PCM_16BIT -> packet.bytes
                    C.ENCODING_PCM_FLOAT -> packet.bytes.floatPcmToPcm16LittleEndian()
                    else -> continue
                }
                val normalizedPcm = converter.convert(
                    bytes = pcm16Bytes,
                    sampleRateHz = packet.sampleRateHz,
                    channelCount = packet.channelCount,
                )
                for (event in speechGate.push(normalizedPcm)) {
                    when (event) {
                        is PcmSpeechEvent.Audio -> {
                            val bytes = event.samples.toLittleEndianBytes()
                            if (activeRecognizer.acceptWaveForm(bytes, bytes.size)) {
                                val text = resultField(activeRecognizer.result, "text")
                                    .take(MAX_SOURCE_TEXT_CHARS)
                                if (text.isNotBlank()) translations.trySend(text)
                                lastPartialText = ""
                                updateForRun(runId) { previous ->
                                    previous.copy(
                                        phase = LiveCaptionPhase.LISTENING,
                                        sourceText = text,
                                        error = null,
                                    )
                                }
                            } else {
                                val partial = resultField(activeRecognizer.partialResult, "partial")
                                    .take(MAX_SOURCE_TEXT_CHARS)
                                val now = SystemClock.elapsedRealtime()
                                if (partial.isNotBlank() && partial != lastPartialText &&
                                    now - lastPartialUpdateMillis >= PARTIAL_UPDATE_INTERVAL_MILLIS
                                ) {
                                    lastPartialText = partial
                                    lastPartialUpdateMillis = now
                                    setPhase(runId, language, LiveCaptionPhase.TRANSCRIBING, partial)
                                }
                            }
                        }
                        PcmSpeechEvent.EndUtterance -> {
                            if (runId == generation.get()) {
                                setPhase(runId, language, LiveCaptionPhase.LISTENING)
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            setError(runId, language, LiveCaptionError.LOW_MEMORY)
        } catch (_: LinkageError) {
            setError(runId, language, LiveCaptionError.RECOGNITION)
        } catch (_: Exception) {
            setError(runId, language, LiveCaptionError.RECOGNITION)
        } finally {
            audioTap.stopCapture()
            translations.close()
            withContext(NonCancellable) {
                translationJob?.cancelAndJoin()
                runCatching { recognizer?.close() }
                runCatching { model?.close() }
                runCatching { translator?.close() }
            }
        }
    }

    private fun isLowMemoryDevice(): Boolean {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return manager.isLowRamDevice || manager.memoryClass < MINIMUM_MEMORY_CLASS_MB
    }

    private fun setPhase(
        runId: Long,
        language: SpeechLanguage,
        phase: LiveCaptionPhase,
        sourceText: String = _state.value.sourceText,
    ) {
        updateForRun(runId) { current ->
            current.copy(
                phase = phase,
                language = language,
                sourceText = sourceText,
                progressPercent = if (phase == LiveCaptionPhase.DOWNLOADING_SPEECH_MODEL) current.progressPercent else null,
                error = null,
            )
        }
    }

    private fun setError(runId: Long, language: SpeechLanguage, error: LiveCaptionError) {
        updateForRun(runId) {
            it.copy(
                phase = LiveCaptionPhase.ERROR,
                language = language,
                progressPercent = null,
                sourceText = "",
                caption = "",
                captionId = it.captionId + 1,
                error = error,
            )
        }
    }

    private inline fun updateForRun(runId: Long, transform: (LiveCaptionState) -> LiveCaptionState) {
        if (runId == generation.get() && !closed) _state.value = transform(_state.value)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        generation.incrementAndGet()
        sessionJob?.cancel()
        sessionJob = null
        audioTap.stopCapture()
        _state.value = LiveCaptionState()
        closed = true
        workerScope.cancel()
    }

    private companion object {
        const val TARGET_SAMPLE_RATE_HZ = 16_000
        const val MINIMUM_MEMORY_CLASS_MB = 256
        const val MAX_PENDING_TRANSLATIONS = 8
        const val MAX_SOURCE_TEXT_CHARS = 1_200
        const val MAX_CAPTION_CHARS = 1_200
        const val PARTIAL_UPDATE_INTERVAL_MILLIS = 350L
    }
}

private fun resultField(json: String, field: String): String =
    runCatching { JSONObject(json).optString(field).trim() }.getOrDefault("")

private fun ByteArray.floatPcmToPcm16LittleEndian(): ByteArray {
    val sampleCount = size / Float.SIZE_BYTES
    val source = ByteBuffer.wrap(this).order(ByteOrder.nativeOrder())
    val output = ByteArray(sampleCount * Short.SIZE_BYTES)
    for (index in 0 until sampleCount) {
        val value = source.float
        val sample = when {
            !value.isFinite() -> 0
            value <= -1f -> Short.MIN_VALUE.toInt()
            value >= 1f -> Short.MAX_VALUE.toInt()
            else -> (value * Short.MAX_VALUE).toInt()
        }
        output[index * 2] = sample.toByte()
        output[index * 2 + 1] = (sample ushr 8).toByte()
    }
    return output
}

private fun ShortArray.toLittleEndianBytes(): ByteArray {
    val output = ByteArray(size * Short.SIZE_BYTES)
    for (index in indices) {
        val sample = this[index].toInt()
        output[index * 2] = sample.toByte()
        output[index * 2 + 1] = (sample ushr 8).toByte()
    }
    return output
}

private suspend fun <T> Task<T>.awaitCaptionTask(): T = suspendCancellableCoroutine { continuation ->
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

private suspend fun currentCoroutineContextEnsureActive() {
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
}
