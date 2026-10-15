package com.kinou.gameassist.data.language

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class LanguageOption(
    val code: String,
    val displayName: String,
    val flag: String,
    val tag: String
)

object LanguageManager {
    private const val PREFS_NAME = "openmapper_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    const val LANG_SYSTEM = "system"
    const val LANG_FR = "fr"
    const val LANG_EN = "en"
    const val LANG_ES = "es"
    const val LANG_PT = "pt"
    const val LANG_HE = "he"

    val AVAILABLE_LANGUAGES = listOf(
        LanguageOption(LANG_FR, "Français", "🇫🇷", "FR"),
        LanguageOption(LANG_EN, "English", "🇬🇧", "EN"),
        LanguageOption(LANG_ES, "Español", "🇪🇸", "ES"),
        LanguageOption(LANG_PT, "Português", "🇧🇷", "PT"),
        LanguageOption(LANG_HE, "עברית", "🇮🇱", "HE")
    )

    private val _currentLanguageFlow = MutableStateFlow(LANG_SYSTEM)
    val currentLanguageFlow: StateFlow<String> = _currentLanguageFlow.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val language = resolveStartupLanguage(saved, if (appLocales.isEmpty) null else appLocales[0]?.language)
        if (language != saved) prefs.edit().putString(KEY_LANGUAGE, language).apply()
        _currentLanguageFlow.value = language
        applyLanguage(language)
    }

    /**
     * Language to apply at startup. A language picked in Android Settings > Apps > OpenMapper >
     * Language (Android 13+) is the more recent choice, so it wins over the one saved by the in-app
     * picker; otherwise the saved choice is kept. A locale the app does not ship is ignored.
     */
    internal fun resolveStartupLanguage(saved: String, appLocaleLanguage: String?): String {
        // Java and older Android still report Hebrew as the legacy code "iw"
        val fromSettings = appLocaleLanguage?.lowercase()?.let { if (it == "iw") LANG_HE else it }
        return if (fromSettings != null && AVAILABLE_LANGUAGES.any { it.code == fromSettings }) fromSettings else saved
    }

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, langCode).apply()
        _currentLanguageFlow.value = langCode
        applyLanguage(langCode)
    }

    fun getCurrentLanguage(context: Context): String {
        return when (val current = _currentLanguageFlow.value) {
            LANG_FR, LANG_EN, LANG_ES, LANG_PT, LANG_HE -> current
            else -> {
                val sysLang = Locale.getDefault().language.lowercase()
                when {
                    sysLang.startsWith("fr") -> LANG_FR
                    sysLang.startsWith("es") -> LANG_ES
                    sysLang.startsWith("pt") -> LANG_PT
                    sysLang.startsWith("he") || sysLang.startsWith("iw") -> LANG_HE
                    else -> LANG_EN
                }
            }
        }
    }

    fun isFrench(context: Context): Boolean {
        return getCurrentLanguage(context) == LANG_FR
    }

    fun getCurrentDisplayTag(context: Context): String {
        return when (getCurrentLanguage(context)) {
            LANG_FR -> "FR"
            LANG_ES -> "ES"
            LANG_PT -> "PT"
            LANG_HE -> "HE"
            else -> "EN"
        }
    }

    fun getCurrentFlag(context: Context): String {
        return when (getCurrentLanguage(context)) {
            LANG_FR -> "🇫🇷"
            LANG_ES -> "🇪🇸"
            LANG_PT -> "🇧🇷"
            LANG_HE -> "🇮🇱"
            else -> "🇬🇧"
        }
    }

    private fun applyLanguage(langCode: String) {
        val appLocale = when (langCode) {
            LANG_FR -> LocaleListCompat.forLanguageTags("fr")
            LANG_EN -> LocaleListCompat.forLanguageTags("en")
            LANG_ES -> LocaleListCompat.forLanguageTags("es")
            LANG_PT -> LocaleListCompat.forLanguageTags("pt")
            LANG_HE -> LocaleListCompat.forLanguageTags("he")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(appLocale)
    }
}
