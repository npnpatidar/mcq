package com.mcqapp

import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passage JSON round trip (step 3) and the hash decision (D6): passageId
 * changes the hash, passage text never does.
 */
class PassageJsonTest {

    private val fileJson = """
        {
          "version": 1,
          "passages": [
            {"id": "p1", "title": "The Passage", "text": "Shared context body."}
          ],
          "papers": [
            {
              "id": "paper1",
              "title": "Paper",
              "categories": [
                {
                  "id": "c1",
                  "title": "Cat",
                  "questions": [
                    {"id": "q1", "text": "Standalone", "options": ["a", "b"], "correctOptionIds": ["a"]},
                    {"id": "q2", "text": "Member", "options": ["a", "b"], "correctOptionIds": ["b"], "passageId": "p1"}
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesTopLevelPassagesAndMemberIds() {
        val file = LegacyParser.parse(fileJson)
        assertEquals(1, file.passages.size)
        assertEquals("p1", file.passages[0].id)
        assertEquals("The Passage", file.passages[0].title)
        assertEquals("Shared context body.", file.passages[0].elements
            .filterIsInstance<com.mcqapp.domain.ContentElement.TextElement>()
            .joinToString("") { it.text })
        val paper = file.papers.single()
        val questions = paper.categories.single().questions
        assertEquals(null, questions[0].passageId)
        assertEquals("p1", questions[1].passageId)
    }

    @Test
    fun passageIdChangesTheContentHash() {
        val with = ContentHash.of("text", listOf("a"), listOf(null), "p1")
        val without = ContentHash.of("text", listOf("a"), listOf(null), null)
        assertNotEquals(with, without)
        // Same id twice is stable, and the no-passage form matches the legacy hash.
        assertEquals(with, ContentHash.of("text", listOf("a"), listOf(null), "p1"))
        assertEquals(
            ContentHash.of("text", listOf("a"), listOf(null)),
            ContentHash.of("text", listOf("a"), listOf(null), null)
        )
    }

    @Test
    fun passageTextNeverEntersTheHash() {
        // The passage body is not an input at all: only the id travels.
        assertEquals(
            ContentHash.of("text", listOf("a"), listOf(null), "p1"),
            ContentHash.of("text", listOf("a"), listOf(null), "p1")
        )
        // Editing the question's own text still changes the hash.
        assertNotEquals(
            ContentHash.of("text", listOf("a"), listOf(null), "p1"),
            ContentHash.of("text2", listOf("a"), listOf(null), "p1")
        )
    }

    @Test
    fun missingPassageIdParsesAsStandalone() {
        val file = LegacyParser.parse("""{"papers":[{"id":"p","title":"T","categories":[{"id":"c","title":"C","questions":[{"id":"q","text":"x","options":["a"],"correctOptionIds":["a"]}]}]}]}""")
        assertTrue(file.passages.isEmpty())
        assertEquals(null, file.papers.single().categories.single().questions.single().passageId)
    }

    @Test
    fun malformedPassageIsSkippedWithAWarning() {
        val file = LegacyParser.parse("""{"passages":[{"title":"no id"}],"papers":[]}""")
        assertTrue(file.passages.isEmpty())
        assertTrue(file.warnings.any { it.contains("passage") })
    }

    @Test
    fun importFileAssemblyCarriesPassagesThrough() {
        // The preview edits questions only; the parsed passages block must
        // still reach the importer or members import with dangling ids.
        val file = LegacyParser.parse(fileJson)
        val state = com.mcqapp.ui.importscreen.ImportUiState(
            paperTitle = "Paper",
            categoryName = "Cat",
            questions = file.papers.single().categories.single().questions,
            originalFile = file
        )
        val assembled = com.mcqapp.ui.importscreen.buildImportFile(state, file)
        assertEquals(listOf("p1"), assembled.passages.map { it.id })
        val member = assembled.papers.single().categories.single()
            .questions.single { it.id == "q2" }
        assertEquals("p1", member.passageId)
    }

    @Test
    fun importFileAssemblyWithoutAParsedFileHasNoPassages() {
        val state = com.mcqapp.ui.importscreen.ImportUiState(paperTitle = "Paper")
        val assembled = com.mcqapp.ui.importscreen.buildImportFile(state, null)
        assertTrue(assembled.passages.isEmpty())
    }
}
