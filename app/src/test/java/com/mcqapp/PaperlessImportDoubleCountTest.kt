package com.mcqapp

import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.FALLBACK_CATEGORY_TITLE
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.PaperDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Every paperless shape wraps its questions in one synthesized "Uncategorized"
 * category. The wrapper must not *also* carry them on [PaperDto.questions]:
 * [com.mcqapp.data.io.Importer] would then synthesize a second `-root`
 * category and upsert every question twice. These are pure JVM — they reproduce
 * the importer's DTO-level category calculation without Room or Robolectric.
 */
class PaperlessImportDoubleCountTest {

    private val keyedMcq = """
        {"id":"q1","text":"Keyed?","options":[{"id":"a","text":"A"},{"id":"b","text":"B"}],"correctOptionIds":["a"]}
    """.trimIndent()

    private val flashcard = """
        {"id":"f1","text":"Front?","explanation":"Back"}
    """.trimIndent()

    private val keylessMcq = """
        {"id":"k1","text":"Keyless?","options":[{"id":"a","text":"A"},{"id":"b","text":"B"}]}
    """.trimIndent()

    private val allKinds = listOf("q1", "f1", "k1")

    /** Mirrors Importer.kt:274-290 — the categories whose questions get upserted. */
    private fun importerQuestionIds(paper: PaperDto): List<String> {
        val effectiveCategories = if (paper.categories.isEmpty()) {
            listOf(
                CategoryDto(
                    id = "${paper.id}-uncat",
                    title = FALLBACK_CATEGORY_TITLE,
                    questions = paper.topLevelQuestions()
                )
            )
        } else if (paper.questions.isNotEmpty()) {
            paper.categories + CategoryDto(
                id = "${paper.id}-root",
                title = FALLBACK_CATEGORY_TITLE,
                questions = paper.topLevelQuestions()
            )
        } else {
            paper.categories
        }
        return effectiveCategories.flatMap { it.questions }.map { it.id }
    }

    /** Mirrors Importer.kt:939 — the raw hash inputs before the set dedupes them. */
    private fun hashInputIds(paper: PaperDto): List<String> =
        (paper.categories.flatMap { it.questions } + paper.topLevelQuestions()).map { it.id }

    private fun streamedPapers(json: String): List<PaperDto> =
        mutableListOf<PaperDto>().also { papers ->
            LegacyParser.streamPapers(
                ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
            ) { papers += it }
        }

    @Test
    fun rootQuestionsAreOwnedOnceByTheUncategorizedCategory() {
        val json = """{"questions":[$keyedMcq,$flashcard,$keylessMcq]}"""
        val file = LegacyParser.parse(json)

        assertEquals("no warnings: ${file.warnings}", emptyList<String>(), file.warnings)
        val paper = file.papers.single()
        assertTrue(paper.id.startsWith(LegacyParser.EPHEMERAL_PAPER_ID_PREFIX))
        val uncat = paper.categories.single { it.title == FALLBACK_CATEGORY_TITLE }
        assertEquals(allKinds, uncat.questions.map { it.id })
        assertEquals(uncat.questions.size, uncat.questions.map { it.id }.toSet().size)
        assertTrue("root questions must not also sit on the paper DTO", paper.questions.isEmpty())
    }

    @Test
    fun importerCountsEachRootQuestionOnce() {
        val json = """{"questions":[$keyedMcq,$flashcard,$keylessMcq]}"""
        val paper = LegacyParser.parse(json).papers.single()

        assertEquals("effective categories upsert each question once", allKinds, importerQuestionIds(paper))
        assertEquals("hash snapshot sees each question once", allKinds, hashInputIds(paper))
    }

    @Test
    fun rootCategoriesBesideRootQuestionsAreNotUpsertedTwice() {
        val json = """
        {
          "categories": [
            {"id":"c1","title":"One","questions":[$keyedMcq]},
            {"id":"c2","title":"Two","questions":[$flashcard]}
          ],
          "questions": [$keylessMcq]
        }
        """.trimIndent()
        val paper = LegacyParser.parse(json).papers.single()

        assertEquals(
            listOf("c1", "c2", "${paper.id}-uncat"),
            paper.categories.map { it.id }
        )
        assertEquals(allKinds, paper.categories.flatMap { it.questions }.map { it.id })
        assertTrue(paper.questions.isEmpty())
        assertEquals(allKinds, importerQuestionIds(paper))
        assertEquals(allKinds, hashInputIds(paper))

        // The streaming twin must agree: one category copy per question.
        val streamed = streamedPapers(json).single()
        assertEquals(allKinds, streamed.categories.flatMap { it.questions }.map { it.id })
        assertEquals(allKinds, importerQuestionIds(streamed))
    }

    private data class PaperlessCase(val label: String, val json: String, val expected: List<String>)

    @Test
    fun everyPaperlessShapeStoresEveryQuestionExactlyOnce() {
        val cases = listOf(
            PaperlessCase("bare array", "[$keyedMcq,$flashcard]", listOf("q1", "f1")),
            PaperlessCase("root questions", """{"questions":[$keyedMcq,$flashcard]}""", listOf("q1", "f1")),
            PaperlessCase(
                "DOCX wrapper",
                """{"version":1,"questions":[$keyedMcq,$flashcard],
                   "passages":[{"id":"p1","text":"Passage."}]}""",
                listOf("q1", "f1")
            ),
            PaperlessCase(
                "root categories",
                """{"categories":[{"id":"c1","title":"One","questions":[$keyedMcq]},
                   {"id":"c2","title":"Two","questions":[$flashcard]}]}""",
                listOf("q1", "f1")
            ),
            PaperlessCase(
                "root categories + root questions",
                """{"categories":[{"id":"c1","title":"One","questions":[$keyedMcq]}],
                   "questions":[$flashcard]}""",
                listOf("q1", "f1")
            )
        )

        cases.forEach { (label, json, expected) ->
            val file = LegacyParser.parse(json)
            assertEquals("$label: no warnings ${file.warnings}", emptyList<String>(), file.warnings)
            val paper = file.papers.single()
            assertEquals("$label: one copy of every question in categories", expected, paper.categories.flatMap { it.questions }.map { it.id })
            assertTrue("$label: no duplicate copy on the paper DTO", paper.questions.isEmpty())
            assertEquals("$label: importer counts each question once", expected, importerQuestionIds(paper))
            assertEquals("$label: hash snapshot counts each question once", expected, hashInputIds(paper))

            val streamed = streamedPapers(json).single()
            assertEquals(
                "$label: streaming path agrees with the whole-file path",
                expected,
                streamed.categories.flatMap { it.questions }.map { it.id }
            )
            assertEquals("$label: streaming DTO counts each question once", expected, importerQuestionIds(streamed))
            assertEquals("$label: streaming hash snapshot must count each question once", expected, hashInputIds(streamed))
        }
    }
}
