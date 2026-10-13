package com.kinou.gameassist.data.language

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LanguageManagerTest {

    @Test
    fun testLanguageConstants() {
        assertEquals("system", LanguageManager.LANG_SYSTEM)
        assertEquals("fr", LanguageManager.LANG_FR)
        assertEquals("en", LanguageManager.LANG_EN)
        assertEquals("es", LanguageManager.LANG_ES)
        assertEquals("pt", LanguageManager.LANG_PT)
        assertEquals(4, LanguageManager.AVAILABLE_LANGUAGES.size)
    }

    @Test
    fun testSystemLanguageDetectionLogic() {
        val frenchLocale = Locale("fr", "FR")
        assertTrue(frenchLocale.language.startsWith("fr", ignoreCase = true))

        val spanishLocale = Locale("es", "ES")
        assertTrue(spanishLocale.language.startsWith("es", ignoreCase = true))

        val portugueseLocale = Locale("pt", "BR")
        assertTrue(portugueseLocale.language.startsWith("pt", ignoreCase = true))

        val englishLocale = Locale("en", "US")
        assertTrue(englishLocale.language.startsWith("en", ignoreCase = true))

        val germanLocale = Locale("de", "DE")
        assertFalse(germanLocale.language.startsWith("fr", ignoreCase = true))
        assertFalse(germanLocale.language.startsWith("es", ignoreCase = true))
        assertFalse(germanLocale.language.startsWith("pt", ignoreCase = true))
    }

    @Test
    fun languagePickedInAndroidSettingsWinsOverTheSavedOne() {
        assertEquals("es", LanguageManager.resolveStartupLanguage(saved = "fr", appLocaleLanguage = "es"))
        assertEquals("pt", LanguageManager.resolveStartupLanguage(saved = LanguageManager.LANG_SYSTEM, appLocaleLanguage = "pt"))
    }

    @Test
    fun savedLanguageIsKeptWhenAndroidHasNoAppLanguage() {
        assertEquals("fr", LanguageManager.resolveStartupLanguage(saved = "fr", appLocaleLanguage = null))
        assertEquals(LanguageManager.LANG_SYSTEM, LanguageManager.resolveStartupLanguage(saved = LanguageManager.LANG_SYSTEM, appLocaleLanguage = null))
    }

    @Test
    fun androidAppLanguageWeDoNotShipIsIgnored() {
        assertEquals("fr", LanguageManager.resolveStartupLanguage(saved = "fr", appLocaleLanguage = "de"))
    }

    @Test
    fun androidAppLanguageIsMatchedIgnoringCase() {
        assertEquals("en", LanguageManager.resolveStartupLanguage(saved = "fr", appLocaleLanguage = "EN"))
    }
}
