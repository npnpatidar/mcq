package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyParserTest {

    @Test
    fun parsesCanonicalSchema() {
        val json = """
        {
          "version": 1,
          "papers": [{
            "id": "p1",
            "title": "Paper",
            "categories": [{
              "id": "c1",
              "title": "Cat",
              "questions": [{
                "id": "q1",
                "text": "Question?",
                "options": [
                  {"id": "a", "text": "A"},
                  {"id": "b", "text": "B"}
                ],
                "correctOptionIds": ["b"],
                "explanation": "Because"
              }]
            }]
          }]
        }
        """.trimIndent()

        val file = LegacyParser.parse(json)
        assertEquals(1, file.papers.size)
        val paper = file.papers[0]
        assertEquals("p1", paper.id)
        assertEquals(1, paper.categories.size)
        val question = paper.categories[0].questions[0]
        assertEquals("q1", question.id)
        assertEquals(listOf("b"), question.correctOptionIds)
        assertEquals("Because", question.explanation)
    }

    @Test
    fun parsesLegacyStringOptionsWithAnswerField() {
        val json = """
        {
          "papers": [{
            "title": "Legacy",
            "questions": [{
              "question": "Pick one",
              "options": ["Alpha", "Beta", "Gamma"],
              "answer": "Beta"
            }]
          }]
        }
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val question = file.papers[0].categories[0].questions[0]
        assertEquals("Pick one", question.text)
        assertEquals(3, question.options.size)
        assertEquals(listOf("b"), question.correctOptionIds)
    }

    @Test
    fun parsesLegacyCorrectIndex() {
        val json = """
        {
          "papers": [{
            "title": "Legacy",
            "questions": [{
              "text": "Pick one",
              "options": ["Alpha", "Beta"],
              "correctIndex": 1
            }]
          }]
        }
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val question = file.papers[0].categories[0].questions[0]
        assertEquals(listOf("b"), question.correctOptionIds)
    }

    @Test
    fun bareArrayParsesUseUniqueCategoryIdsPerPaper() {
        val json = """
        [
          {"question": "Q1", "options": ["A", "B"]},
          {"question": "Q2", "options": ["C", "D"]}
        ]
        """.trimIndent()

        val first = LegacyParser.parse(json)
        val second = LegacyParser.parse(json)
        assertEquals(1, first.papers.size)
        assertEquals(1, second.papers.size)
        val firstCat = first.papers[0].categories[0].id
        val secondCat = second.papers[0].categories[0].id
        assertTrue(firstCat.isNotBlank())
        assertTrue(secondCat.isNotBlank())
        // Same paper id and category id on re-parse would REPLACE the category
        // row and cascade-delete the first import's questions.
        assertTrue(
            "category ids must differ across parses (got $firstCat twice)",
            firstCat != secondCat
        )
        assertTrue(
            "category id must belong to its paper",
            firstCat.startsWith(first.papers[0].id)
        )
    }

    @Test
    fun bareArrayQuestionIdsAreStableAcrossParses() {
        val json = """
        [
          {"question": "Q1", "options": ["A", "B"]},
          {"question": "Q2", "options": ["C", "D"]}
        ]
        """.trimIndent()

        val first = LegacyParser.parse(json).papers[0].categories[0].questions.map { it.id }
        val second = LegacyParser.parse(json).papers[0].categories[0].questions.map { it.id }
        assertEquals(first, second)
        assertEquals(2, first.toSet().size)
    }

    @Test
    fun duplicateQuestionsGetUniqueIds() {
        val json = """
        [
          {"question": "Same", "options": ["A", "B"]},
          {"question": "Same", "options": ["A", "B"]}
        ]
        """.trimIndent()

        val ids = LegacyParser.parse(json).papers[0].categories[0].questions.map { it.id }
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun generatesIdsWhenMissing() {
        val json = """
        {
          "papers": [{
            "title": "No IDs",
            "questions": [{
              "text": "Q",
              "options": ["A", "B"],
              "correct": "A"
            }]
          }]
        }
        """.trimIndent()

        val file = LegacyParser.parse(json)
        assertTrue(file.papers[0].id.isNotBlank())
        assertTrue(file.papers[0].categories[0].id.isNotBlank())
        assertTrue(file.papers[0].categories[0].questions[0].id.isNotBlank())
        assertEquals(listOf("a"), file.papers[0].categories[0].questions[0].correctOptionIds)
    }

    @Test
    fun samplePaperAssetIsComprehensive() {
        val asset = java.io.File("src/main/assets/sample_paper.json")
        assertTrue("asset missing: ${asset.absolutePath}", asset.isFile)
        val paper = LegacyParser.parse(asset.readText()).papers.single()
        val questions = paper.categories.flatMap { it.questions }
        assertTrue("expected >= 14 questions, got ${questions.size}", questions.size >= 14)
        var questionImages = 0
        var optionImages = 0
        var explanationImages = 0
        for (q in questions) {
            assertTrue("blank text: ${q.id}", q.text.isNotBlank())
            assertTrue("fewer than 2 options: ${q.id}", q.options.size >= 2)
            val optionIds = q.options.map { it.id }.toSet()
            assertTrue("unresolved correct ids: ${q.id}",
                q.correctOptionIds.isNotEmpty() && q.correctOptionIds.all { it in optionIds })
            assertTrue("missing explanation: ${q.id}", q.explanation.isNotBlank())
            if (isPngDataUri(q.image)) questionImages++
            if (isPngDataUri(q.explanationImage)) explanationImages++
            optionImages += q.options.count { isPngDataUri(it.image) }
        }
        assertTrue("expected question images, got $questionImages", questionImages >= 2)
        assertTrue("expected option images, got $optionImages", optionImages >= 5)
        assertTrue("expected explanation images, got $explanationImages", explanationImages >= 1)
    }

    private fun isPngDataUri(src: String?): Boolean {
        if (src == null || !src.startsWith("data:image/png;base64,")) return false
        return try {
            val bytes = java.util.Base64.getMimeDecoder()
                .decode(src.substringAfter(","))
            bytes.size > 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    @Test
    fun parsesExplanationImageAliases() {
        val json = """
        {
          "papers": [{
            "title": "T",
            "questions": [
              {"text": "Q1", "options": ["A", "B"], "explanationImage": "data:image/png;base64,AAA"},
              {"text": "Q2", "options": ["A", "B"], "explanation_image": "https://example.com/e.png"},
              {"text": "Q3", "options": ["A", "B"]}
            ]
          }]
        }
        """.trimIndent()

        val questions = LegacyParser.parse(json).papers[0].categories[0].questions
        assertEquals("data:image/png;base64,AAA", questions[0].explanationImage)
        assertEquals("https://example.com/e.png", questions[1].explanationImage)
        assertEquals(null, questions[2].explanationImage)
    }
}
