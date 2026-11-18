package com.mcqapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The language picker must not lose a label (or a placeholder) in Hindi.
 * Mirrors FinalErrorResourcesTest: read both XML files from disk, compare the
 * names and the format specifiers instead of trusting a translation by eye.
 */
class LanguagePickerResourcesTest {

    private val specifier = Regex("""%\d+\$[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")

    private fun entries(path: String): Map<String, String> =
        Regex(
            """<string name="(language[a-z0-9_]*)"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun englishAndHindiResourcesMatchNamesAndPlaceholders() {
        val en = entries("src/main/res/values/strings_language_picker.xml")
        val hi = entries("src/main/res/values-hi/strings_language_picker_hi.xml")
        assertTrue("the English picker must define its labels", en.isNotEmpty())
        assertEquals("English and Hindi picker names differ", en.keys, hi.keys)
        en.forEach { (name, value) ->
            assertEquals(
                "$name must bind the same placeholders in both locales",
                specifier.findAll(value).map { it.value }.toList(),
                specifier.findAll(hi.getValue(name)).map { it.value }.toList()
            )
        }
    }

    @Test
    fun everyChoiceHasALabel() {
        val names = entries("src/main/res/values/strings_language_picker.xml").keys
        assertTrue(
            "the picker needs a heading, help text and three choices: $names",
            names.containsAll(
                listOf(
                    "language", "language_system", "language_english",
                    "language_hindi", "language_help"
                )
            )
        )
    }
}
