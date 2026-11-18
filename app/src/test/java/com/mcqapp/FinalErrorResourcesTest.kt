package com.mcqapp

import com.mcqapp.data.io.FALLBACK_CATEGORY_TITLE
import com.mcqapp.data.io.FALLBACK_PAPER_TITLE
import com.mcqapp.ui.importscreen.ImportUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the localized friendly-error batch: every English resource must have a
 * Hindi twin with the same name and the same format placeholders, and the
 * persisted fallback-title tokens must keep their canonical values (out-of-scope
 * readers compare those titles by string equality).
 */
class FinalErrorResourcesTest {

    private val specifier = Regex("""%\d+\$[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]""")

    private fun finalEntries(path: String): Map<String, String> {
        val xml = File(path).readText()
        return Regex("""<string name="(final_[a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun englishAndHindiFinalErrorResourcesMatchNamesAndPlaceholders() {
        val en = finalEntries("src/main/res/values/strings_final_errors_b.xml")
        val hi = finalEntries("src/main/res/values-hi/strings_final_errors_b_hi.xml")
        assertTrue("the English batch must define friendly errors", en.isNotEmpty())
        assertEquals("English and Hindi name sets differ", en.keys, hi.keys)
        en.forEach { (name, value) ->
            assertEquals(
                "$name must bind the same placeholders in both locales",
                specifier.findAll(value).map { it.value }.toList(),
                specifier.findAll(hi.getValue(name)).map { it.value }.toList()
            )
        }
    }

    @Test
    fun restoreAndSharedFileErrorsKeepTheirPlaceholders() {
        val en = finalEntries("src/main/res/values/strings_final_errors_b.xml")
        assertEquals(
            listOf("%1\$d"),
            specifier.findAll(en.getValue("final_restore_too_large")).map { it.value }.toList()
        )
        assertEquals(
            listOf("%1\$d", "%2\$d"),
            specifier.findAll(en.getValue("final_import_too_large")).map { it.value }.toList()
        )
        assertTrue(en.getValue("final_restore_failed").isNotBlank())
    }

    @Test
    fun fallbackTitleTokensKeepTheirCanonicalValues() {
        assertEquals("Uncategorized", FALLBACK_CATEGORY_TITLE)
        assertEquals("Imported Questions", FALLBACK_PAPER_TITLE)
        assertEquals(FALLBACK_CATEGORY_TITLE, ImportUiState().categoryName)
    }
}
