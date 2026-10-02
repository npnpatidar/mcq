package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Partial-import tolerance is deliberate, but a row that is dropped must say
 * so. These cases used to look like a successful import of fewer questions.
 */
class ImportRowDiagnosticsTest {

    private fun warnings(json: String) = LegacyParser.parse(json).warnings

    @Test
    fun aMalformedPaperIsReportedByPosition() {
        val w = warnings("""{"papers":[{"id":"p1","title":"Good","categories":[]}, 42]}""")
        assertTrue("warnings were: $w", w.any { it.contains("malformed paper 2") })
    }

    @Test
    fun aMalformedCategoryNamesThePaper() {
        val w = warnings(
            """{"papers":[{"id":"p1","title":"Physics","categories":[{"id":"c1","title":"C","questions":[]}, "oops"]}]}"""
        )
        assertTrue("warnings were: $w", w.any { it.contains("category 2") && it.contains("Physics") })
    }

    @Test
    fun aCategoryWithABrokenQuestionsArrayIsStillReported() {
        // "questions" must be an array; a bare string is a malformed category.
        val w = warnings("{\"papers\":[{\"id\":\"p1\",\"title\":\"P\",\"categories\":[{\"id\":\"c1\",\"title\":\"C\",\"questions\":\"oops\"}]}]}")
        assertTrue("warnings were: $w", w.isNotEmpty())
    }

    @Test
    fun aMalformedAttemptIsReported() {
        val w = warnings("""{"papers":[],"attempts":[42]}""")
        assertTrue("warnings were: $w", w.any { it.contains("malformed attempt 1") })
    }

    @Test
    fun aCleanFileProducesNoWarnings() {
        val file = LegacyParser.parse(
            """{"papers":[{"id":"p1","title":"P","categories":[{"id":"c1","title":"C",
               "questions":[{"id":"q1","text":"Q","options":[{"id":"a","text":"A"}],"correct":"a"}]}]}]}"""
        )
        assertTrue("warnings were: ${file.warnings}", file.warnings.isEmpty())
    }
}
