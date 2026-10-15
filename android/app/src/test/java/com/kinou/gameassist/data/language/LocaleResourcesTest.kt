package com.kinou.gameassist.data.language

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the translations: every locale must define exactly the strings of the default
 * resources (no missing key that silently falls back to English), keep the same format
 * arguments, and the locale list shown by Android must match the in-app language picker.
 * Runs on the JVM and reads the XML sources, so it needs no emulator.
 */
class LocaleResourcesTest {

    private val resDir = File("src/main/res")

    private fun strings(dir: String): Map<String, String> {
        val file = File(resDir, "$dir/strings.xml")
        assertTrue("missing ${file.path}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val node = nodes.item(it)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private fun localeDirs(): List<String> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
            .map { it.name }
            .sorted()

    private val formatArg = Regex("%(\\d+[$])?[sdf]")

    @Test
    fun everyLocaleDefinesExactlyTheDefaultKeys() {
        val default = strings("values").keys
        for (dir in localeDirs()) {
            val keys = strings(dir).keys
            assertEquals("$dir is missing keys", emptySet<String>(), default - keys)
            assertEquals("$dir has extra keys", emptySet<String>(), keys - default)
        }
    }

    @Test
    fun everyLocaleKeepsTheFormatArguments() {
        val default = strings("values")
        for (dir in localeDirs()) {
            for ((key, text) in strings(dir)) {
                val expected = formatArg.findAll(default.getValue(key)).map { it.value }.sorted().toList()
                val actual = formatArg.findAll(text).map { it.value }.sorted().toList()
                assertEquals("$dir/$key format arguments", expected, actual)
            }
        }
    }

    @Test
    fun noLocaleHasABlankString() {
        for (dir in listOf("values") + localeDirs()) {
            for ((key, text) in strings(dir)) {
                assertTrue("$dir/$key is blank", text.isNotBlank())
            }
        }
    }

    @Test
    fun englishLocaleMatchesTheDefaultResources() {
        assertEquals(strings("values"), strings("values-en"))
    }

    @Test
    fun androidLocaleConfigListsTheSameLanguagesAsThePicker() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resDir, "xml/locales_config.xml"))
        val nodes = doc.getElementsByTagName("locale")
        val declared = (0 until nodes.length)
            .map { nodes.item(it).attributes.getNamedItem("android:name").nodeValue }
            .toSet()
        assertEquals(LanguageManager.AVAILABLE_LANGUAGES.map { it.code }.toSet(), declared)
    }

    @Test
    fun manifestPointsToTheLocaleConfig() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""))
    }

    /** Android keeps the legacy resource qualifier "iw" for Hebrew. */
    private fun resourceDir(code: String) = "values-" + if (code == "he") "iw" else code

    @Test
    fun everyPickerLanguageHasItsResources() {
        for (language in LanguageManager.AVAILABLE_LANGUAGES) {
            val dir = if (language.code == LanguageManager.LANG_EN) "values-en" else resourceDir(language.code)
            assertTrue("missing $dir for ${language.code}", File(resDir, "$dir/strings.xml").exists())
        }
    }

    @Test
    fun noStringContainsAnInvisibleZeroWidthSpace() {
        for (dir in listOf("values") + localeDirs()) {
            for ((key, text) in strings(dir)) {
                assertTrue("$dir/$key contains U+200B", !text.contains(0x200B.toChar()))
            }
        }
    }

    @Test
    fun hebrewStringsAreWrittenInHebrew() {
        val hebrewLetters = Regex("[א-ת]")
        // Names and abbreviations that are the same in every language
        val allowed = setOf("app_name", "gamepad_btn_bluetooth", "overlay_role_ads_badge", "language_fr", "language_en", "language_es", "language_pt")
        for ((key, text) in strings("values-iw")) {
            if (key in allowed) continue
            assertTrue("values-iw/$key has no Hebrew letter: $text", hebrewLetters.containsMatchIn(text))
        }
    }
}
