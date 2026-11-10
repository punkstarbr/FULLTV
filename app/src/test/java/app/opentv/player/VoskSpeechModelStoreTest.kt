/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VoskSpeechModelStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val modelsDirectory: File
        get() = File(context.noBackupFilesDir, "live-caption-models")

    @Before
    fun setUp() {
        modelsDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        modelsDirectory.deleteRecursively()
    }

    @Test
    fun catalogContainsTheReferenceLanguagesAndOfficialSmallModels() {
        assertThat(SpeechLanguage.entries).hasSize(13)
        assertThat(SpeechLanguage.entries.map { it.displayName }).containsExactly(
            "English-US",
            "Español",
            "Français",
            "Deutsch",
            "Italiano",
            "Русский",
            "中文",
            "日本語",
            "Nederlands",
            "Türkçe",
            "Tiếng Việt",
            "हिन्दी",
            "Português",
        ).inOrder()
        assertThat(SpeechLanguage.entries.map { it.modelName }).containsExactly(
            "vosk-model-small-en-us-0.15",
            "vosk-model-small-es-0.42",
            "vosk-model-small-fr-0.22",
            "vosk-model-small-de-0.15",
            "vosk-model-small-it-0.22",
            "vosk-model-small-ru-0.22",
            "vosk-model-small-cn-0.22",
            "vosk-model-small-ja-0.22",
            "vosk-model-small-nl-0.22",
            "vosk-model-small-tr-0.3",
            "vosk-model-small-vn-0.4",
            "vosk-model-small-hi-0.22",
            "vosk-model-small-pt-0.3",
        ).inOrder()
        assertThat(SpeechLanguage.entries.map { it.bcp47Tag }).containsExactly(
            "en-US",
            "es-ES",
            "fr-FR",
            "de-DE",
            "it-IT",
            "ru-RU",
            "zh-CN",
            "ja-JP",
            "nl-NL",
            "tr-TR",
            "vi-VN",
            "hi-IN",
            "pt-BR",
        ).inOrder()
    }

    @Test
    fun validModelIsInstalledAndThenReusedWithoutAnotherDownload() {
        runBlocking {
            val requests = AtomicInteger(0)
            val archive = validArchive(SpeechLanguage.ENGLISH)
            val client = archiveClient(archive) { requests.incrementAndGet() }
            val store = VoskSpeechModelStore(context, client)
            val progress = mutableListOf<Int?>()

            val first = store.ensureModel(SpeechLanguage.ENGLISH) { progress += it }
            val second = store.ensureModel(SpeechLanguage.ENGLISH)

            assertThat(first).isEqualTo(second)
            assertThat(File(first, "am/final.mdl").isFile).isTrue()
            assertThat(File(first, "conf/model.conf").isFile).isTrue()
            assertThat(requests.get()).isEqualTo(1)
            assertThat(progress).contains(0)
            assertThat(progress.last()).isEqualTo(100)
            assertThat(modelsDirectory.listFiles()!!.map { it.name })
                .containsExactly(SpeechLanguage.ENGLISH.modelName)
        }
    }

    @Test
    fun zipSlipPathsAreRejectedAndTemporaryFilesAreRemoved() = runBlocking {
        val archive = zipOf(
            "${SpeechLanguage.ENGLISH.modelName}/am/final.mdl" to byteArrayOf(1),
            "../outside.txt" to byteArrayOf(2),
        )
        val store = VoskSpeechModelStore(context, archiveClient(archive))

        val failure = runCatching { store.ensureModel(SpeechLanguage.ENGLISH) }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(File(context.noBackupFilesDir, "outside.txt").exists()).isFalse()
        assertThat(modelsDirectory.listFiles().orEmpty().toList()).isEmpty()
    }

    @Test
    fun backslashAndAbsoluteZipPathsAreRejected() = runBlocking {
        val unsafeNames = listOf(
            "${SpeechLanguage.ENGLISH.modelName}\\am\\final.mdl",
            "/absolute.txt",
            "C:/absolute.txt",
        )
        for (unsafeName in unsafeNames) {
            modelsDirectory.deleteRecursively()
            val store = VoskSpeechModelStore(
                context,
                archiveClient(zipOf(unsafeName to byteArrayOf(1))),
            )

            assertThat(runCatching { store.ensureModel(SpeechLanguage.ENGLISH) }.isFailure)
                .isTrue()
            assertThat(modelsDirectory.listFiles().orEmpty().toList()).isEmpty()
        }
    }

    @Test
    fun compressedSizeAndEntryCountLimitsAreEnforcedWithoutNetwork() = runBlocking {
        val oversizedBody = object : ResponseBody() {
            private val delegate = ByteArray(1).toResponseBody("application/zip".toMediaType())

            override fun contentType() = delegate.contentType()

            override fun contentLength(): Long = VoskSpeechModelStore.MAX_COMPRESSED_BYTES + 1L

            override fun source() = delegate.source()
        }
        val oversizedClient = responseClient { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(oversizedBody)
                .build()
        }
        val oversizedStore = VoskSpeechModelStore(context, oversizedClient)
        assertThat(runCatching { oversizedStore.ensureModel(SpeechLanguage.ENGLISH) }.isFailure)
            .isTrue()
        assertThat(modelsDirectory.listFiles().orEmpty().toList()).isEmpty()

        val tooManyEntries = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                repeat(VoskSpeechModelStore.MAX_ZIP_ENTRIES + 1) { index ->
                    zip.putNextEntry(ZipEntry("entry-$index"))
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
        val entryLimitStore = VoskSpeechModelStore(context, archiveClient(tooManyEntries))
        assertThat(runCatching { entryLimitStore.ensureModel(SpeechLanguage.ENGLISH) }.isFailure)
            .isTrue()
        assertThat(modelsDirectory.listFiles().orEmpty().toList()).isEmpty()
    }

    private fun validArchive(language: SpeechLanguage): ByteArray = zipOf(
        "${language.modelName}/am/final.mdl" to byteArrayOf(1, 2, 3),
        "${language.modelName}/conf/model.conf" to "sample-rate=16000".encodeToByteArray(),
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun archiveClient(archive: ByteArray, onRequest: () -> Unit = {}): OkHttpClient =
        responseClient { request ->
            onRequest()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(archive.toResponseBody("application/zip".toMediaType()))
                .build()
        }

    private fun responseClient(responseFactory: (okhttp3.Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> responseFactory(chain.request()) })
            .build()
}
