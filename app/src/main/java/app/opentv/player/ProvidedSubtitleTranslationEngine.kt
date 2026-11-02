/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class ProvidedSubtitleTranslationPhase {
    WAITING_FOR_SOURCE_SUBTITLES,
    TRANSLATING,
    DOWNLOADING_TRANSLATION_MODEL,
    READY,
    ERROR,
}

internal data class ProvidedSubtitleTranslationState(
    val phase: ProvidedSubtitleTranslationPhase = ProvidedSubtitleTranslationPhase.WAITING_FOR_SOURCE_SUBTITLES,
    val caption: String = "",
)

/** Extracts readable text cues only; bitmap-only subtitles are intentionally not OCR'd. */
internal object SubtitleCueText {
    fun from(cues: Iterable<CharSequence?>): List<String> = cues
        .mapNotNull { cue -> cue?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
}

/**
 * Translates text emitted by the stream's selected subtitle track. It has no audio input and cannot
 * create captions when the channel does not provide readable subtitle cues.
 */
internal class ProvidedSubtitleTranslationEngine : AutoCloseable {
    private data class Request(val generation: Long, val text: String)

    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private val generation = AtomicLong(0)
    private val _state = MutableStateFlow(ProvidedSubtitleTranslationState())
    val state: StateFlow<ProvidedSubtitleTranslationState> = _state.asStateFlow()

    private var lastSubmittedText = ""
    private var languageIdentifier: com.google.mlkit.nl.languageid.LanguageIdentifier? = null
    private val translators = mutableMapOf<String, Translator>()
    private val downloadedTranslationModels = mutableSetOf<String>()
    @Volatile private var closed = false

    init {
        workerScope.launch {
            for (request in requests) {
                if (request.text.isBlank()) continue
                try {
                    val translated = translateToPortuguese(request.text)
                    if (request.generation == generation.get() && !closed) {
                        _state.value = ProvidedSubtitleTranslationState(
                            phase = ProvidedSubtitleTranslationPhase.READY,
                            caption = translated.ifBlank { request.text },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (request.generation == generation.get() && !closed) {
                        // Keep the channel's own track visible; do not replace failure with ASR.
                        _state.value = ProvidedSubtitleTranslationState(
                            phase = ProvidedSubtitleTranslationPhase.ERROR,
                        )
                    }
                }
            }
        }
    }

    /** Called only with text cues received from the selected media subtitle track. */
    @Synchronized
    fun submitCues(cues: Iterable<CharSequence?>) {
        if (closed) return
        val text = SubtitleCueText.from(cues)
            .joinToString("\n")
            .take(MAX_SOURCE_TEXT_CHARS)
        if (text == lastSubmittedText) return
        lastSubmittedText = text
        val request = Request(generation.incrementAndGet(), text)
        if (text.isBlank()) {
            _state.value = ProvidedSubtitleTranslationState()
        } else {
            _state.value = ProvidedSubtitleTranslationState(
                phase = ProvidedSubtitleTranslationPhase.TRANSLATING,
            )
        }
        requests.trySend(request)
    }

    private suspend fun translateToPortuguese(text: String): String {
        val identifier = languageIdentifier ?: LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(LANGUAGE_CONFIDENCE_THRESHOLD)
                .build(),
        ).also { languageIdentifier = it }
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
            _state.value = ProvidedSubtitleTranslationState(
                phase = ProvidedSubtitleTranslationPhase.DOWNLOADING_TRANSLATION_MODEL,
            )
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitResult()
            downloadedTranslationModels += sourceLanguage
        }
        return translator.translate(text).awaitResult()
    }

    override fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        requests.close()
        workerScope.cancel()
        languageIdentifier?.close()
        languageIdentifier = null
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    private companion object {
        const val LANGUAGE_CONFIDENCE_THRESHOLD = 0.2f
        const val MAX_SOURCE_TEXT_CHARS = 2_000
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        if (continuation.isActive) continuation.cancel(CancellationException("Translation task cancelled"))
    }
}
