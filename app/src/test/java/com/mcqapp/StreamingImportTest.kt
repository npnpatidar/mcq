package com.mcqapp

import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The streaming reader must be indistinguishable from [LegacyParser.parse]:
 * a streaming import that reordered, dropped, or renamed a row relative to the
 * whole-file reader would silently corrupt every bank it touched. These tests
 * reconstruct the DTO the callbacks describe and compare it, field for field,
 * with the whole-file parse of the same input.
 */
class StreamingImportTest {

    /** Reassembles an [McqFileDto] from the stream, in document order. */
    private class Collector : LegacyParser.ImportStreamSink {
        class P(
            val id: String,
            val title: String,
            val description: String,
            val duration: Int,
            val negative: Double
        ) {
            val categories = mutableListOf<C>()
        }

        class C(val id: String, val title: String, val parentId: String?) {
            val questions = mutableListOf<QuestionDto>()
        }

        val papers = mutableListOf<P>()
        var tail: LegacyParser.StreamedFile? = null

        override fun onPaper(header: LegacyParser.PaperHeader) {
            papers += P(header.id, header.title, header.description, header.durationMinutes, header.negativeMarking)
        }

        override fun onCategory(category: CategoryDto) {
            papers.last().categories += C(category.id, category.title, category.parentId)
        }

        override fun onQuestions(categoryId: String, questions: List<QuestionDto>) {
            papers.last().categories.last { it.id == categoryId }.questions += questions
        }

        fun asDto(): McqFileDto = McqFileDto(
            version = tail?.version ?: 1,
            papers = papers.map { p ->
                PaperDto(
                    id = p.id,
                    title = p.title,
                    description = p.description,
                    durationMinutes = p.duration,
                    negativeMarking = p.negative,
                    categories = p.categories.map { CategoryDto(it.id, it.title, it.parentId, it.questions.toList()) }
                )
            },
            bookmarks = tail?.bookmarks ?: emptyList(),
            attempts = tail?.attempts ?: emptyList(),
            warnings = tail?.warnings ?: emptyList(),
            scheduling = tail?.scheduling ?: emptyMap(),
            passages = tail?.passages ?: emptyList()
        )
    }

    private fun collect(json: String, batchSize: Int = LegacyParser.IMPORT_BATCH_SIZE): Collector {
        val collector = Collector()
        collector.tail = LegacyParser.streamImport(
            ByteArrayInputStream(json.toByteArray()),
            collector,
            batchSize
        )
        return collector
    }

    @Test
    fun streamingReassemblesExactlyTheWholeFileParse() {
        val json = """
        {
          "version": 3,
          "papers": [
            {
              "id": "p1",
              "title": "Paper One",
              "description": "desc",
              "duration": 60,
              "negative": 0.25,
              "categories": [
                {"id": "c1", "title": "Cat A", "questions": [
                  {"id": "q1", "text": "One?", "options": ["a", "b"], "correctOptionIds": ["a"]},
                  {"id": "q2", "text": "Two?", "options": ["c", "d"], "correctOptionIds": ["d"],
                   "passageId": "ps1", "difficulty": "hard", "marks": 2, "tags": ["x", "y"]}
                ]},
                {"id": "c2", "title": "Empty Cat"},
                {"id": "c3", "title": "Cat C", "questions": [
                  {"id": "q1", "text": "duplicate id?", "options": ["e", "f"], "correctOptionIds": ["e"]}
                ]}
              ],
              "questions": [
                {"id": "root1", "text": "Root?", "options": ["g", "h"], "correctOptionIds": ["g"]}
              ]
            },
            {
              "id": "p2",
              "title": "Paper Two",
              "categories": [
                {"id": "c4", "title": "Only", "questions": [
                  {"id": "q9", "text": "Nine?", "options": ["i", "j"], "correctOptionIds": ["i"]}
                ]}
              ]
            }
          ],
          "bookmarks": ["q1", "q9"],
          "attempts": [
            {"paperId": "p1", "title": "T", "totalQuestions": 1, "correctCount": 1}
          ],
          "scheduling": {
            "q1": {"ease": 2.4, "intervalDays": 3, "dueAt": 123, "reps": 2}
          },
          "passages": [
            {"id": "ps1", "title": "Passage", "text": "Read this"}
          ]
        }
        """.trimIndent()

        val collector = collect(json)
        assertEquals(LegacyParser.parse(json), collector.asDto())
    }

    @Test
    fun streamingPreservesTopLevelQuestionsAndTheMalformedRowWarnings() {
        val json = """
        {
          "version": 2,
          "papers": [
            {
              "id": "p1",
              "title": "P",
              "categories": [
                {"id": "c1", "title": "C", "questions": "not a list"}
              ],
              "questions": [
                {"id": "t1", "text": "Top?", "options": ["a", "b"], "correctOptionIds": ["a"]},
                42
              ]
            }
          ]
        }
        """.trimIndent()

        val collector = collect(json)
        assertEquals(LegacyParser.parse(json).papers, collector.asDto().papers)
        assertEquals(LegacyParser.parse(json).warnings, collector.asDto().warnings)
    }

    @Test
    fun batchesRespectTheBatchSizeAndArriveInOrder() {
        val questions = (1..5).joinToString(",") {
            """{"id": "q$it", "text": "Q$it?", "options": ["a", "b"], "correctOptionIds": ["a"]}"""
        }
        val json = """{"papers":[{"id":"p","title":"T","categories":[{"id":"c","title":"C","questions":[$questions]}]}]}"""

        val sizes = mutableListOf<Int>()
        val ids = mutableListOf<String>()
        val sink = object : LegacyParser.ImportStreamSink {
            override fun onPaper(header: LegacyParser.PaperHeader) = Unit
            override fun onCategory(category: CategoryDto) = Unit
            override fun onQuestions(categoryId: String, questions: List<QuestionDto>) {
                sizes += questions.size
                ids += questions.map { it.id }
            }
        }
        LegacyParser.streamImport(ByteArrayInputStream(json.toByteArray()), sink, batchSize = 2)

        assertEquals(listOf(2, 2, 1), sizes)
        assertEquals(listOf("q1", "q2", "q3", "q4", "q5"), ids)
    }

    @Test
    fun streamsABareQuestionsArrayAsOneEphemeralPaper() {
        val json = """
        [
          {"id": "q1", "text": "One?", "options": ["a", "b"], "correctOptionIds": ["a"]},
          {"id": "q2", "text": "Two?", "options": ["c", "d"], "correctOptionIds": ["d"]}
        ]
        """.trimIndent()

        val collector = collect(json)
        val paper = collector.papers.single()
        assertTrue(paper.id.startsWith(LegacyParser.EPHEMERAL_PAPER_ID_PREFIX))
        assertEquals("Imported Questions", paper.title)
        val category = paper.categories.single()
        assertEquals("Uncategorized", category.title)
        assertEquals(listOf("q1", "q2"), category.questions.map { it.id })
    }

    @Test
    fun streamsTheDocxRootQuestionsWrapperAsOnePaper() {
        val json = """
        {
          "questions": [
            {"id": "d1", "text": "One?", "options": ["a", "b"], "correctOptionIds": ["a"]}
          ],
          "passages": [
            {"id": "ps", "title": "P", "text": "Body"}
          ]
        }
        """.trimIndent()

        val collector = collect(json)
        val paper = collector.papers.single()
        assertTrue(paper.id.startsWith(LegacyParser.EPHEMERAL_PAPER_ID_PREFIX))
        assertEquals(listOf("d1"), paper.categories.single().questions.map { it.id })
        assertEquals(listOf("ps"), collector.tail!!.passages.map { it.id })
    }
}
