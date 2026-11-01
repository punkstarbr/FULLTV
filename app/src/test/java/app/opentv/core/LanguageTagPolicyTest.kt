package app.opentv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageTagPolicyTest {
    @Test
    fun missingPreferenceDefaultsToBrazilianPortuguese() {
        assertEquals("pt-BR", LanguageTagPolicy.fromStoredValue(null))
    }

    @Test
    fun explicitSystemLanguageChoiceRemainsBlank() {
        assertEquals("", LanguageTagPolicy.fromStoredValue(""))
    }

    @Test
    fun legacyGenericPortugueseChoiceMapsToPortugal() {
        assertEquals("pt-PT", LanguageTagPolicy.fromStoredValue("pt"))
    }

    @Test
    fun explicitBrazilAndPortugalChoicesArePreserved() {
        assertEquals("pt-BR", LanguageTagPolicy.fromStoredValue("pt-BR"))
        assertEquals("pt-PT", LanguageTagPolicy.fromStoredValue("pt-PT"))
    }
}
