package app.opentv.player

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SpeechLanguageTest {
    @Test
    fun exposesMlKitBaseLanguageCodesForEveryVoskModel() {
        SpeechLanguage.entries.forEach { language ->
            assertEquals(language.bcp47Tag.substringBefore('-'), language.translationLanguageCode)
            assertNotNull(TranslateLanguage.fromLanguageTag(language.translationLanguageCode))
        }
    }

    @Test
    fun mapsRegionAndIsoThreeLetterAudioMetadata() {
        assertSame(SpeechLanguage.ENGLISH, SpeechLanguage.fromAudioTrackLanguageTag("en-US"))
        assertSame(SpeechLanguage.ENGLISH, SpeechLanguage.fromAudioTrackLanguageTag("eng"))
        assertSame(SpeechLanguage.PORTUGUESE, SpeechLanguage.fromAudioTrackLanguageTag("pt-BR"))
        assertSame(SpeechLanguage.PORTUGUESE, SpeechLanguage.fromAudioTrackLanguageTag("por"))
        assertSame(SpeechLanguage.CHINESE, SpeechLanguage.fromAudioTrackLanguageTag("zh-CN"))
        assertNull(SpeechLanguage.fromAudioTrackLanguageTag("und"))
        assertNull(SpeechLanguage.fromAudioTrackLanguageTag(null))
    }
}
