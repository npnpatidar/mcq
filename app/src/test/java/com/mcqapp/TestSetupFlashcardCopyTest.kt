package com.mcqapp

import com.mcqapp.domain.TestPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The copy a flashcards-only scope shows: "No multiple-choice questions here —
 * flashcards are study-only" exists in both locales, and the English resource
 * says exactly what the pure [TestPlan] constant says. The asked/run line a
 * panel or blueprint needs is also present in both locales.
 *
 * Pure JVM, no Android resources loaded: it reads the XML files the way the
 * other localisation guards do.
 */
class TestSetupFlashcardCopyTest {

    private val stringEntry = Regex("<string name=\"([A-Za-z0-9_]+)\">(.*?)</string>")

    private fun entries(path: String): Map<String, String> =
        stringEntry.findAll(File(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    private val english by lazy { entries("src/main/res/values/strings.xml") }
    private val hindi by lazy { entries("src/main/res/values-hi/strings_hi_8.xml") }
    private val hindiWide by lazy { entries("src/main/res/values-hi/strings_hi_7.xml") }

    @Test
    fun theFlashcardsOnlyLineExistsInBothLocalesAndMatchesTheDomainCopy() {
        val en = english["test_setup_scope_flashcards_only"]
        assertEquals(TestPlan.NO_MULTIPLE_CHOICE_MESSAGE, en)
        val hi = hindi["test_setup_scope_flashcards_only"]
        assertTrue("the Hindi copy must exist", hi != null && hi.isNotEmpty())
        assertNotEquals("the Hindi copy must be translated", en, hi)
        assertTrue(
            "the Hindi copy must be Devanagari, not English text",
            hi!!.any { it in '\u0900'..'\u097F' }
        )
    }

    @Test
    fun theAskedRunLineExistsInBothLocales() {
        // A frozen set that shrank says "asked X · ran Y" instead of silently
        // running fewer; both locales must carry it.
        assertTrue(english.containsKey("test_setup_asked_run"))
        assertTrue(
            hindi.containsKey("test_setup_asked_run") ||
                hindiWide.containsKey("test_setup_asked_run")
        )
    }
}
