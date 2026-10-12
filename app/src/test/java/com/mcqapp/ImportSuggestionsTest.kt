package com.mcqapp

import com.mcqapp.ui.importscreen.docxPaperTitle
import com.mcqapp.ui.importscreen.suggestNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportSuggestionsTest {

    @Test
    fun docxTitleIsTheFileNameWithoutItsExtension() {
        assertEquals("Physics Bank", docxPaperTitle("Physics Bank.docx"))
        assertEquals("bank.v2", docxPaperTitle("bank.v2.docx"))
    }

    @Test
    fun docxTitleToleratesMissingOrBlankNames() {
        assertNull(docxPaperTitle(null))
        assertNull(docxPaperTitle(""))
        assertNull(docxPaperTitle("   "))
        assertNull(docxPaperTitle(".docx"))
    }

    @Test
    fun suggestNamesMatchesIgnoringCaseAndOrdersPrefixesFirst() {
        val existing = listOf("Physics", "Maths", "Applied Physics")
        assertEquals(
            listOf("Physics", "Applied Physics"),
            suggestNames("phys", existing)
        )
    }

    @Test
    fun suggestNamesBlankQueryOffersTheLibrary() {
        assertEquals(
            listOf("Physics", "Maths"),
            suggestNames("", listOf("Physics", "Maths", "Chemistry"), limit = 2)
        )
    }

    @Test
    fun suggestNamesDropsNonMatchesAndBlanks() {
        assertEquals(
            emptyList<String>(),
            suggestNames("zzz", listOf("Physics", "", "Maths"))
        )
    }

    @Test
    fun suggestNamesDeduplicatesCaseInsensitivelyAndHonoursLimit() {
        // "PHYSICS" collides with "physics" case-insensitively; "Physics 2"
        // is a distinct name and stays.
        assertEquals(
            listOf("physics", "Physics 2"),
            suggestNames("phy", listOf("physics", "PHYSICS", "Physics 2"))
        )
        assertEquals(
            1,
            suggestNames("phy", listOf("physics", "Physics 2"), limit = 1).size
        )
    }
}
