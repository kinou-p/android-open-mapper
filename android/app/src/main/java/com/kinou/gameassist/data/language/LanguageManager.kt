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

    val AVAILABLE_LANGUAGES = listOf(
        LanguageOption(LANG_FR, "Français", "🇫🇷", "FR"),
        LanguageOption(LANG_EN, "English", "🇬🇧", "EN"),
        LanguageOption(LANG_ES, "Español", "🇪🇸", "ES"),
        LanguageOption(LANG_PT, "Português", "🇧🇷", "PT")
    )

    private val _currentLanguageFlow = MutableStateFlow(LANG_SYSTEM)
    val currentLanguageFlow: StateFlow<String> = _currentLanguageFlow.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedLang = prefs.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
        _currentLanguageFlow.value = savedLang
        applyLanguage(savedLang)
    }

    fun setLanguage(context: Context, langCode: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, langCode).apply()
        _currentLanguageFlow.value = langCode
        applyLanguage(langCode)
    }

    fun getCurrentLanguage(context: Context): String {
        return when (val current = _currentLanguageFlow.value) {
            LANG_FR, LANG_EN, LANG_ES, LANG_PT -> current
            else -> {
                val sysLang = Locale.getDefault().language.lowercase()
                when {
                    sysLang.startsWith("fr") -> LANG_FR
                    sysLang.startsWith("es") -> LANG_ES
                    sysLang.startsWith("pt") -> LANG_PT
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
            else -> "EN"
        }
    }

    fun getCurrentFlag(context: Context): String {
        return when (getCurrentLanguage(context)) {
            LANG_FR -> "🇫🇷"
            LANG_ES -> "🇪🇸"
            LANG_PT -> "🇧🇷"
            else -> "🇬🇧"
        }
    }

    private fun applyLanguage(langCode: String) {
        val appLocale = when (langCode) {
            LANG_FR -> LocaleListCompat.forLanguageTags("fr")
            LANG_EN -> LocaleListCompat.forLanguageTags("en")
            LANG_ES -> LocaleListCompat.forLanguageTags("es")
            LANG_PT -> LocaleListCompat.forLanguageTags("pt")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(appLocale)
    }
}
