package com.mcqapp

import com.mcqapp.data.io.LegacyParser
import com.mcqapp.domain.ContentElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class LegacyParserTest {

    /**
     * A deeply nested value under a key the walker does not read is skipped.
     * The skip used to recurse one frame per level, so a *valid* file that
     * was merely deep crashed the whole-file import with an uncaught
     * StackOverflowError — the slice reader's own depth budget never came
     * into play, because the value is never read. `skipValue` in
     * [com.mcqapp.data.io.JsonPull] now walks the containers iteratively.
     */
    @Test
    fun deeplyNestedSkippedValuesDoNotOverflowTheStack() {
        // A root key nothing reads, holding 50_000 nested arrays: well past
        // any stack, trivially legal JSON.
        val depth = 50_000
        val json = "{\"version\":1,\"papers\":[],\"junk\":" +
            "[".repeat(depth) + "]".repeat(depth) + "}"
        val file = LegacyParser.parse(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
        assertEquals(0, file.papers.size)
    }

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
    fun parsesMarksWithAliasesAndFallbacks() {        val json = """
        {
          "papers": [{
            "id": "p1",
            "title": "Paper",
            "categories": [{
              "id": "c1",
              "title": "Cat",
              "questions": [
                {"id": "q1", "text": "A?", "options": ["X", "Y"], "marks": 3},
                {"id": "q2", "text": "B?", "options": ["X", "Y"], "points": 2.5},
                {"id": "q3", "text": "C?", "options": ["X", "Y"], "weight": 0},
                {"id": "q4", "text": "D?", "options": ["X", "Y"]},
                {"id": "q5", "text": "E?", "options": ["X", "Y"], "marks": -2},
                {"id": "q6", "text": "F?", "options": ["X", "Y"], "marks": "lots"}
              ]
            }]
          }]
        }
        """.trimIndent()

        val questions = LegacyParser.parse(json).papers.single()
            .categories.single().questions
        assertEquals(3.0, questions[0].marks, 0.0001)
        assertEquals(2.5, questions[1].marks, 0.0001)
        assertEquals(0.0, questions[2].marks, 0.0001)
        assertEquals(1.0, questions[3].marks, 0.0001)
        assertEquals(1.0, questions[4].marks, 0.0001)
        assertEquals(1.0, questions[5].marks, 0.0001)
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
        // Paper ids derive from wall-clock millis: wait for the clock to tick
        // so the two parses cannot share an id (same-ms parses would flake).
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() == start) Thread.sleep(1)
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
        val file = LegacyParser.parse(asset.readText())
        // The shipped demo must import without a single complaint.
        assertEquals("sample asset produced warnings: ${file.warnings}", emptyList<String>(), file.warnings)
        val paper = file.papers.single()
        val questions = paper.categories.flatMap { it.questions }
        assertTrue("expected >= 20 questions, got ${questions.size}", questions.size >= 20)
        // top-level questions land in their own category instead of being dropped
        val root = paper.categories.filter { it.title == "Uncategorized" }
        assertEquals("expected exactly one Uncategorized root category", 1, root.size)
        assertTrue("expected the top-level question to land in Uncategorized",
            root[0].questions.any { it.id == "q-top-1" })
        assertTrue("nested categories expected",
            paper.categories.any { it.parentId == "cat-science" })

        var questionImages = 0
        var optionImages = 0
        var explanationImages = 0
        var mathElements = 0
        var questionTables = 0
        var optionTables = 0
        var explanationTables = 0
        var scalarQuestionImages = 0
        var scalarOptionImages = 0
        var scalarExplanationImages = 0
        var missingExplanation = 0
        var missingAnswer = 0
        var multiCorrect = 0
        var hindi = 0
        val unknownMathTags = sortedSetOf<String>()
        for (q in questions) {
            assertTrue("blank id", q.id.isNotBlank())
            assertTrue("blank text: ${q.id}", q.text.isNotBlank())
            assertTrue("unexpected option count: ${q.id}",
                q.options.size >= 2 || q.id == "q-e2" || q.id == "q-e5")
            val optionIds = q.options.map { it.id }.toSet()
            assertTrue("unresolved correct ids: ${q.id}",
                q.correctOptionIds.all { it in optionIds })
            if (q.correctOptionIds.size > 1) multiCorrect++
            if (DEVANAGARI.containsMatchIn(q.text)) hindi++
            if (q.explanation.isBlank()) missingExplanation++
            if (q.correctOptionIds.isEmpty()) missingAnswer++

            for (element in q.elements + q.explanationElements) {
                if (element is ContentElement.MathElement) {
                    unknownMathTags += MATHML_TAG.findAll(element.mathml)
                        .map { it.groupValues[1] }
                        .filterNot { it in MATHML_TAGS }
                }
            }
            questionImages += q.elements.count { it is ContentElement.ImageElement }
            explanationImages += q.explanationElements.count { it is ContentElement.ImageElement }
            mathElements += (q.elements + q.explanationElements).count { it is ContentElement.MathElement }
            questionTables += q.elements.count { it is ContentElement.TableElement }
            explanationTables += q.explanationElements.count { it is ContentElement.TableElement }
            for (option in q.options) {
                optionImages += option.elements.count { it is ContentElement.ImageElement }
                optionTables += option.elements.count { it is ContentElement.TableElement }
            }
            // The legacy scalar shapes are still accepted and still demonstrated.
            if (isPngDataUri(q.image)) scalarQuestionImages++
            if (isPngDataUri(q.explanationImage)) scalarExplanationImages++
            scalarOptionImages += q.options.count { isPngDataUri(it.image) }
        }

        // Rich content: pictures, formulas and tables in all three positions.
        assertTrue("expected pictures in questions, got $questionImages", questionImages >= 3)
        assertTrue("expected pictures in options, got $optionImages", optionImages >= 4)
        assertTrue("expected pictures in explanations, got $explanationImages", explanationImages >= 2)
        assertTrue("expected MathML formulas, got $mathElements", mathElements >= 8)
        assertTrue(
            "the sample bank's MathML uses tags MathJax does not accept: " +
                "$unknownMathTags — it renders as a bare \"Math input error\"",
            unknownMathTags.isEmpty()
        )
        assertTrue("expected tables in questions, got $questionTables", questionTables >= 2)
        assertTrue("expected tables in options, got $optionTables", optionTables >= 1)
        assertTrue("expected tables in explanations, got $explanationTables", explanationTables >= 2)
        // Legacy scalar picture fields remain in the demo.
        assertTrue("expected a scalar question image", scalarQuestionImages >= 1)
        assertTrue("expected a scalar option image", scalarOptionImages >= 1)
        assertTrue("expected a scalar explanation image", scalarExplanationImages >= 1)

        assertTrue("expected questions with several correct options, got $multiCorrect",
            multiCorrect >= 6)
        assertTrue("expected Hindi questions, got $hindi", hindi >= 5)
        assertTrue("expected edge questions without explanation, got $missingExplanation",
            missingExplanation >= 2)
        assertTrue("expected edge questions without answer, got $missingAnswer",
            missingAnswer >= 2)

        // Distinct text keeps the .apkg round trip able to tell questions apart.
        assertEquals("duplicate question text in the sample bank",
            questions.size, questions.map { it.text }.toSet().size)

        // Varied weights for shuffle/marks verification.
        val marks = paper.categories.single { it.title == "Order Check and Marks" }
            .questions.associate { it.id to it.marks }
        assertEquals(2.0, marks["q-o1"]!!, 0.0001)
        assertEquals(1.0, marks["q-o2"]!!, 0.0001)
        assertEquals(3.0, marks["q-o3"]!!, 0.0001)

        // Reading passages: two groups, every member resolves, one standalone
        // question shares the category without joining a passage, and the
        // passage body carries rich content (a table) like a question can.
        assertEquals(
            "expected the two demo passages",
            listOf("passage-1", "passage-2"),
            file.passages.map { it.id }
        )
        assertEquals("The Water Cycle", file.passages[0].title)
        assertEquals("खाद्य शृंखला", file.passages[1].title)
        assertTrue(
            "the demo passage should carry a table",
            file.passages[0].elements.any { it is ContentElement.TableElement }
        )
        val passageIds = file.passages.map { it.id }.toSet()
        val members = questions.filter { !it.passageId.isNullOrBlank() }
        assertEquals("expected five passage members", 5, members.size)
        assertTrue(
            "a member points at a passage that is not in the file",
            members.all { it.passageId in passageIds }
        )
        assertTrue(
            "the standalone passage-category question should have no passage",
            questions.single { it.id == "q-psg-6" }.passageId.isNullOrBlank()
        )
    }

    private val DEVANAGARI = Regex("[\\u0900-\\u097F]")

    private val MATHML_TAG = Regex("</?([a-zA-Z][a-zA-Z0-9]*)")

    /** The subset of MathML the bundled MathJax build accepts. */
    private val MATHML_TAGS = setOf(
        "math", "mrow", "mi", "mn", "mo", "mtext", "mspace", "ms", "mlabeledtr",
        "msub", "msup", "msubsup", "mfrac", "msqrt", "mroot", "mstyle", "mpadded",
        "mphantom", "menclose", "mfenced", "mtable", "mtr", "mtd", "munder",
        "mover", "munderover", "merror", "semantics", "annotation", "maction"
    )

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
    fun topLevelQuestionsCoexistWithCategories() {
        val json = """
        {
          "papers": [{
            "id": "p1",
            "title": "Paper",
            "categories": [{
              "id": "c1",
              "title": "Cat",
              "questions": [{"id": "q1", "text": "In cat?", "options": ["A", "B"]}]
            }],
            "questions": [{"id": "q2", "text": "Top level?", "options": ["A", "B"]}]
          }]
        }
        """.trimIndent()

        val paper = LegacyParser.parse(json).papers.single()
        assertEquals(2, paper.categories.size)
        val root = paper.categories.single { it.title == "Uncategorized" }
        assertEquals(listOf("q2"), root.questions.map { it.id })
        assertEquals(listOf("q1"), paper.categories.single { it.id == "c1" }.questions.map { it.id })
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

    @Test(expected = RuntimeException::class)
    fun malformedJsonThrows() {
        LegacyParser.parse("{not json at all")
    }

    @Test
    fun validJsonWithoutPapersParsesEmpty() {
        val file = LegacyParser.parse("""{"version": 1}""")
        assertTrue(file.papers.isEmpty())
        assertTrue(file.bookmarks.isEmpty())
        assertTrue(file.attempts.isEmpty())
        assertTrue(file.warnings.isEmpty())
    }

    @Test
    fun scalarRootReportsDiagnosticInsteadOfSilentEmpty() {
        val file = LegacyParser.parse("42")
        assertTrue(file.papers.isEmpty())
        assertEquals(1, file.warnings.size)
        assertTrue(file.warnings[0].contains("scalar"))
    }

    @Test
    fun malformedPaperIsSkippedWithRowDiagnostic() {
        val json = """
        {
          "papers": [
            {"id": "p1", "title": "Good", "categories": []},
            "not an object"
          ]
        }
        """.trimIndent()

        val file = LegacyParser.parse(json)
        assertEquals(1, file.papers.size)
        assertEquals("p1", file.papers[0].id)
        assertEquals(1, file.warnings.size)
        assertTrue(file.warnings[0].contains("paper 2"))
    }

    @Test
    fun malformedQuestionRowIsSkippedWithRowDiagnostic() {
        val json = """
        {
          "papers": [{
            "id": "p1",
            "title": "Paper",
            "categories": [{
              "id": "c1",
              "title": "Cat",
              "questions": [
                {"id": "q1", "text": "Fine?", "options": ["A", "B"], "correctOptionIds": ["a"]},
                42
              ]
            }]
          }]
        }
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val questions = file.papers[0].categories[0].questions
        assertEquals(1, questions.size)
        assertEquals("q1", questions[0].id)
        assertEquals(1, file.warnings.size)
        assertTrue(file.warnings[0].contains("question 2"))
    }

    @Test
    fun parsesStructuredElementsFormat() {
        val json = """
        [
          {
            "question_num": "1.)",
            "question_elements": [
              {"type": "text", "content": "Match the kings:"},
              {"type": "table", "content": [["King", "Year"], ["Akbar", "1556"]]}
            ],
            "options_elements": {
              "a": [{"type": "text", "content": "Jalal"}],
              "b": [{"type": "text", "content": "Mansingh"}]
            },
            "answer": "a",
            "explanation_elements": [
              {"type": "text", "content": "Jalal was sent in 1572."}
            ]
          }
        ]
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val question = file.papers[0].categories[0].questions[0]
        // question_num is a bank serial, not content: it must not leak into
        // the elements or the text.
        assertEquals(
            listOf(
                com.mcqapp.domain.ContentElement.TextElement("Match the kings:"),
                com.mcqapp.domain.ContentElement.TableElement(
                    listOf(listOf("King", "Year"), listOf("Akbar", "1556"))
                )
            ),
            question.elements
        )
        assertEquals("Match the kings:", question.text)
        assertEquals(
            listOf("a"),
            question.correctOptionIds
        )
        assertEquals(
            listOf(com.mcqapp.domain.ContentElement.TextElement("Jalal")),
            question.options[0].elements
        )
        assertEquals(
            listOf(com.mcqapp.domain.ContentElement.TextElement("Jalal was sent in 1572.")),
            question.explanationElements
        )
    }

    @Test
    fun imageElementContentExtractsTheImgSrc() {
        val json = """
        [
          {
            "question_elements": [
              {"type": "text", "content": "Identify the shape:"}
            ],
            "options_elements": {
              "a": [{"type": "image", "content": "<br/><img src=\"/tmp/q100/img/fig20.png\" />"}]
            },
            "answer": "a"
          }
        ]
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val question = file.papers[0].categories[0].questions[0]
        assertEquals(
            listOf(com.mcqapp.domain.ContentElement.ImageElement("/tmp/q100/img/fig20.png")),
            question.options[0].elements
        )
    }

    @Test
    fun oldStringFormatStillParsesWithElementsPopulated() {
        val json = """
        [
          {"text": "Plain question?", "options": ["A", "B"], "answer": "a"}
        ]
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val question = file.papers[0].categories[0].questions[0]
        assertEquals(
            listOf(com.mcqapp.domain.ContentElement.TextElement("Plain question?")),
            question.elements
        )
        assertEquals(
            listOf(com.mcqapp.domain.ContentElement.TextElement("A")),
            question.options[0].elements
        )
    }

    @Test
    fun cleanFileProducesNoDiagnostics() {
        val json = """
        {
          "papers": [{
            "id": "p1",
            "title": "Paper",
            "categories": [{
              "id": "c1",
              "title": "Cat",
              "questions": [
                {"id": "q1", "text": "Fine?", "options": ["A", "B"], "correctOptionIds": ["a"]}
              ]
            }]
          }]
        }
        """.trimIndent()

        val file = LegacyParser.parse(json)
        assertTrue(file.warnings.isEmpty())
    }

    @Test
    fun questionNumIsIgnoredWithPlainText() {
        val json = """
        [
          {"question_num": "10.)", "text": "What is 2+2?", "options": ["3", "4"], "answer": "b"}
        ]
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val question = file.papers[0].categories[0].questions[0]
        assertEquals(
            listOf(com.mcqapp.domain.ContentElement.TextElement("What is 2+2?")),
            question.elements
        )
        assertEquals("What is 2+2?", question.text)
    }

    @Test
    fun embeddedMathBlocksBecomeMathElements() {
        val json = """
        [
          {
            "question_elements": [
              {"type": "text", "content": "Solve <math><mi>x</mi></math> then <MATH><mn>2</mn></MATH> done"}
            ],
            "options": ["A"],
            "answer": "a"
          }
        ]
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val question = file.papers[0].categories[0].questions[0]
        assertEquals(
            listOf(
                com.mcqapp.domain.ContentElement.TextElement("Solve "),
                com.mcqapp.domain.ContentElement.MathElement("<math><mi>x</mi></math>"),
                com.mcqapp.domain.ContentElement.TextElement(" then "),
                com.mcqapp.domain.ContentElement.MathElement("<MATH><mn>2</mn></MATH>"),
                com.mcqapp.domain.ContentElement.TextElement(" done")
            ),
            question.elements
        )
        assertEquals("Solve  then  done", question.text)
    }

    @Test
    fun unclosedMathStaysPlainText() {
        val json = """
        [
          {
            "question_elements": [
              {"type": "text", "content": "Broken <math><mi>x</mi> formula"}
            ],
            "options": ["A"],
            "answer": "a"
          }
        ]
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val question = file.papers[0].categories[0].questions[0]
        assertEquals(
            listOf(
                com.mcqapp.domain.ContentElement.TextElement("Broken <math><mi>x</mi> formula")
            ),
            question.elements
        )
    }

    @Test
    fun localImagePathsWarnOnceWithExample() {
        val json = """
        [
          {
            "id": "q1",
            "question_elements": [
              {"type": "text", "content": "See figure:"},
              {"type": "image", "content": "<img src=\"/tmp/q100/img/fig20.png\" />"}
            ],
            "options": ["A"],
            "answer": "a"
          },
          {
            "id": "q2",
            "question_elements": [
              {"type": "image", "content": "<img src=\"file:///sdcard/img/fig21.png\" />"}
            ],
            "options": ["A"],
            "answer": "a"
          }
        ]
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val questions = file.papers[0].categories[0].questions
        // Parsing itself is exact: the srcs survive verbatim.
        assertEquals(
            com.mcqapp.domain.ContentElement.ImageElement("/tmp/q100/img/fig20.png"),
            questions[0].elements[1]
        )
        assertEquals(1, file.warnings.size)
        assertTrue(file.warnings[0].contains("2 image(s) in 2 question(s)"))
        assertTrue(file.warnings[0].contains("/tmp/q100/img/fig20.png"))
    }

    @Test
    fun portableImageSourcesWarnNothing() {
        val json = """
        [
          {
            "id": "q1",
            "question_elements": [
              {"type": "image", "content": "data:image/png;base64,iVBORw0KGgo="},
              {"type": "image", "content": "<img src=\"https://example.com/fig.png\" />"},
              {"type": "image", "content": "<img src=\"content://media/external/images/1\" />"}
            ],
            "options": ["A"],
            "answer": "a"
          }
        ]
        """.trimIndent()

        val file = LegacyParser.parse(json)
        val question = file.papers[0].categories[0].questions[0]
        assertEquals(3, question.elements.size)
        assertEquals(
            com.mcqapp.domain.ContentElement.ImageElement("content://media/external/images/1"),
            question.elements[2]
        )
        assertTrue(file.warnings.isEmpty())
    }

    // --- scheduling: the FSRS memory model travels beside the legacy columns ---

    private fun schedulingJson(scheduling: String) = """
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
                "options": [{"id": "a", "text": "A"}],
                "correctOptionIds": ["a"]
              }]
            }]
          }],
          "scheduling": $scheduling
        }
        """.trimIndent()

    @Test
    fun parsesTheFsrsMemoryModelOutOfSchedulingEntries() {
        val file = LegacyParser.parse(
            schedulingJson(
                """{"q1": {"ease": 2.5, "intervalDays": 3, "dueAt": 123,
                     "reps": 2, "lapses": 1, "leech": true, "lastReviewedAt": 99,
                     "difficulty": 5.4, "stability": 3.2,
                     "cardState": "REVIEW", "reviewCount": 7}}"""
            )
        )
        val schedule = file.scheduling["q1"]!!
        // The FSRS memory model survives the parse untouched — this is the
        // regression the dropped-fields fix guards.
        assertEquals(5.4, schedule.difficulty, 0.0001)
        assertEquals(3.2, schedule.stability, 0.0001)
        assertEquals("REVIEW", schedule.cardState)
        assertEquals(7, schedule.reviewCount)
        // And the legacy columns still travel with it.
        assertEquals(3, schedule.intervalDays)
        assertEquals(123L, schedule.dueAt)
        assertEquals(1, schedule.lapses)
        assertTrue(schedule.leech)
    }

    @Test
    fun aLegacySchedulingEntryDefaultsItsMemoryModel() {
        val file = LegacyParser.parse(
            schedulingJson(
                """{"q1": {"ease": 2.5, "intervalDays": 0, "dueAt": 0,
                     "reps": 0, "lapses": 0, "leech": false, "lastReviewedAt": 0}}"""
            )
        )
        val schedule = file.scheduling["q1"]!!
        // No FSRS fields in the file: the card imports at new rather than
        // pretending to know a stability it never carried.
        assertEquals(0.0, schedule.difficulty, 0.0001)
        assertEquals(0.0, schedule.stability, 0.0001)
        assertEquals("NEW", schedule.cardState)
        assertEquals(0, schedule.reviewCount)
    }

    @Test
    fun corruptSchedulingValuesFallBackToDefaultsInsteadOfThrowing() {
        // One unreadable entry costs that card's schedule, never the import:
        // wrong-typed numbers, an unknown state name, a non-object entry and
        // a non-object scheduling map are all tolerated.
        val file = LegacyParser.parse(
            schedulingJson(
                """{"q1": {"difficulty": "oops", "stability": true,
                     "cardState": "banana", "reviewCount": "many"},
                    "q2": 7},
                   "schedulingExtra": []"""
            )
        )
        val schedule = file.scheduling["q1"]!!
        assertEquals(0.0, schedule.difficulty, 0.0001)
        assertEquals(0.0, schedule.stability, 0.0001)
        assertEquals("NEW", schedule.cardState)
        assertEquals(0, schedule.reviewCount)
        // A non-object entry is skipped wholesale.
        assertEquals(null, file.scheduling["q2"])
    }

    @Test
    fun aNonObjectSchedulingMapYieldsNoSchedules() {
        val json = """
        {
          "version": 1,
          "papers": [],
          "scheduling": ["q1", "q2"]
        }
        """.trimIndent()
        val file = LegacyParser.parse(json)
        assertTrue(file.scheduling.isEmpty())
        assertTrue(file.warnings.isEmpty())
    }

    /**
     * The streaming parse must be indistinguishable from the whole-file one:
     * same papers in the same order, same tail fields, same warnings. Both
     * share one root walk, so this pins that they cannot drift apart.
     */
    @Test
    fun streamedPapersMatchTheWholeFileParse() {
        val json = """
        {
          "version": 3,
          "papers": [
            {"id":"p1","title":"First","categories":[
              {"id":"c1","title":"Cat","questions":[
                {"id":"q1","text":"One?","options":[{"id":"a","text":"A"}],"correctOptionIds":["a"]},
                {"id":"q2","text":"Two?","options":[{"id":"a","text":"A"}],"correctOptionIds":["a"]}]}]},
            {"id":"p2","title":"Second","categories":[
              {"id":"c2","title":"Cat","questions":[
                {"id":"q3","text":"Three?","options":[{"id":"a","text":"A"}],"correctOptionIds":["a"]}]}]}
          ],
          "bookmarks": ["q1"],
          "attempts": [],
          "warnings": [],
          "scheduling": {"q1": {"ease": 2.5}},
          "passages": [
            {"id":"ps1","title":"Read","elements":[{"type":"text","content":"Body"}]}
          ]
        }
        """.trimIndent()

        val whole = LegacyParser.parse(json)
        val streamed = mutableListOf<com.mcqapp.data.io.PaperDto>()
        val tail = LegacyParser.streamPapers(
            ByteArrayInputStream(json.toByteArray())
        ) { streamed += it }

        assertEquals(whole.papers, streamed)
        assertEquals(whole.version, tail.version)
        assertEquals(whole.bookmarks, tail.bookmarks)
        assertEquals(whole.attempts, tail.attempts)
        assertEquals(whole.scheduling, tail.scheduling)
        assertEquals(whole.passages, tail.passages)
        assertEquals(whole.warnings, tail.warnings)
    }

    /** Each paper is handed over as it is read, in file order. */
    @Test
    fun streamedPapersArriveInOrder() {
        val json = """
        {"version":1,"papers":[
          {"id":"p1","title":"A","categories":[]},
          {"id":"p2","title":"B","categories":[]},
          {"id":"p3","title":"C","categories":[]}]}
        """.trimIndent()
        val ids = mutableListOf<String>()
        LegacyParser.streamPapers(ByteArrayInputStream(json.toByteArray())) {
            ids += it.id
        }
        assertEquals(listOf("p1", "p2", "p3"), ids)
    }

    /** The DOCX wrapper shape (`questions`, no `papers`) streams one paper. */
    @Test
    fun streamsTheRootQuestionsWrapperAsOnePaper() {
        val json = """
        {"questions":[
          {"id":"q1","text":"One?","options":[{"id":"a","text":"A"}],"correctOptionIds":["a"]}]}
        """.trimIndent()
        val papers = mutableListOf<com.mcqapp.data.io.PaperDto>()
        LegacyParser.streamPapers(ByteArrayInputStream(json.toByteArray())) { papers += it }
        assertEquals(1, papers.size)
        assertEquals(1, papers[0].categories.flatMap { it.questions }.size)
    }

    /** A bare questions array streams the single ephemeral paper. */
    @Test
    fun streamsABareQuestionsArrayAsOnePaper() {
        val json = """
        [{"id":"q1","text":"One?","options":[{"id":"a","text":"A"}],"correctOptionIds":["a"]}]
        """.trimIndent()
        val papers = mutableListOf<com.mcqapp.data.io.PaperDto>()
        val tail = LegacyParser.streamPapers(ByteArrayInputStream(json.toByteArray())) { papers += it }
        assertEquals(1, papers.size)
        assertTrue(tail.passages.isEmpty())
    }
}
