package com.mcqapp

import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ImportWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportWarningsTest {

    private fun question(
        id: String = "q1",
        text: String = "Text?",
        options: List<OptionDto> = listOf(OptionDto("a", "A"), OptionDto("b", "B")),
        correct: List<String> = listOf("a"),
        marks: Double = 1.0,
        image: String? = null,
        explanation: String = ""
    ) = QuestionDto(
        id = id,
        text = text,
        image = image,
        options = options,
        correctOptionIds = correct,
        explanation = explanation,
        marks = marks
    )

    @Test
    fun cleanQuestionHasNoWarnings() {
        assertEquals(emptyList<ImportWarnings.Warning>(), ImportWarnings.forQuestion(question(), "Q1"))
    }

    @Test
    fun flagsAnswerAndOptionProblems() {
        val warnings = ImportWarnings.forQuestion(
            question(id = "qx", text = " ", options = emptyList(), correct = emptyList()),
            "Q9"
        )
        val messages = warnings.map { it.message }
        assertTrue(messages.any { it.startsWith("Blank text") })
        // Zero options is a flashcard; the problem is an empty back, not a
        // missing answer key or an unanswerable choice.
        assertTrue(messages.any { it.startsWith("No answer yet") })
        assertFalse(messages.any { it.startsWith("No options") })
        assertFalse(messages.any { it.startsWith("No answer key") })
        assertTrue(warnings.all { it.questionId == "qx" && it.label == "Q9" })
    }

    @Test
    fun aFlashcardWithABackHasNoWarning() {
        val warnings = ImportWarnings.forQuestion(
            question(
                options = emptyList(),
                correct = emptyList(),
                explanation = "The answer."
            ),
            "Q2"
        )
        assertEquals(emptyList<String>(), warnings.map { it.message })
    }

    @Test
    fun aFlashcardWithNoBackWarnsItWillShowNothingOnReveal() {
        val warnings = ImportWarnings.forQuestion(
            question(options = emptyList(), correct = emptyList(), explanation = ""),
            "Q3"
        )
        assertEquals(
            listOf("No answer yet — will show nothing on reveal"),
            warnings.map { it.message }
        )
    }

    @Test
    fun flagsSingleOptionDanglingKeyAndZeroMarks() {
        val warnings = ImportWarnings.forQuestion(
            question(
                options = listOf(OptionDto("a", "A")),
                correct = listOf("zzz"),
                marks = 0.0
            ),
            "Q2"
        )
        val messages = warnings.map { it.message }
        // One option is converted to a flashcard before writing, so neither the
        // option count nor a key that names no stored option is a problem.
        assertFalse(messages.any { it.startsWith("Only one option") })
        assertFalse(messages.any { it.startsWith("No answer") })
        assertFalse(messages.any { it.contains("zzz") })
        assertTrue(messages.any { it.startsWith("Worth 0 marks") })
    }

    @Test
    fun aMultipleChoiceKeyThatNamesNoStoredOptionStillWarns() {
        val warnings = ImportWarnings.forQuestion(
            question(
                options = listOf(OptionDto("a", "A"), OptionDto("b", "B")),
                correct = listOf("zzz")
            ),
            "Q4"
        )
        assertTrue(warnings.map { it.message }.any { it.contains("zzz") })
    }

    @Test
    fun flagsLargeImagesByPayloadSize() {
        val big = "x".repeat(ImportWarnings.LARGE_IMAGE_CHARS + 1)
        val warnings = ImportWarnings.forQuestion(question(image = big), "Q3")
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].message.contains("KB"))
        val small = ImportWarnings.forQuestion(question(image = "tiny"), "Q3")
        assertTrue(small.isEmpty())
    }

    @Test
    fun demoEdgeCasesProduceExpectedWarnings() {
        val asset = java.io.File("src/main/assets/sample_paper.json")
        val file = com.mcqapp.data.io.LegacyParser.parse(asset.readText())
        val questions = file.papers.single().categories.flatMap { it.questions }
        val byId = ImportWarnings.forFile(questions).groupBy { it.questionId }
        assertTrue(byId["q-e1"]!!.any { it.message.startsWith("No answer key") })
        // q-e2 has no options but a real explanation: it is a flashcard with a
        // back, not an unanswerable question.
        assertTrue(byId["q-e2"].orEmpty().isEmpty())
        // q-e5 has one option and becomes a flashcard at import: no option or
        // key warning.
        assertTrue(byId["q-e5"].orEmpty().isEmpty())
        assertTrue(byId["q-o3"].orEmpty().isEmpty())
    }

    /**
     * Parser warnings were all shown under "Skipped rows" in error red, beside a
     * second red panel saying the import was fine. Most are advisory, so the
     * heading claimed content had been dropped when it had not.
     */
    @Test
    fun advisoryParserWarningsAreNotReportedAsSkippedRows() {
        val advisory = listOf(
            "File root is a scalar or null, not an object or array — no papers found",
            "Could not read the answer key: nope — question imports ungraded",
            "An equation uses unsupported constructs and was kept as plain text"
        )
        val dropped = listOf(
            "Skipped malformed question 3: expected a list",
            "A picture points at a missing file (media/x.png) and was skipped."
        )
        val groups = com.mcqapp.domain.splitImportWarnings(advisory + dropped)

        assertEquals(dropped.toSet(), groups.skipped.toSet())
        assertEquals(advisory.toSet(), groups.notes.toSet())
        // Nothing may be lost by the split, whichever side it lands on.
        assertEquals(
            (advisory + dropped).toSet(),
            (groups.skipped + groups.notes).toSet()
        )
        assertTrue(!groups.isEmpty)
    }

    @Test
    fun noWarningsMeansNoGroups() {
        val groups = com.mcqapp.domain.splitImportWarnings(emptyList())
        assertTrue(groups.isEmpty)
        assertTrue(groups.skipped.isEmpty())
        assertTrue(groups.notes.isEmpty())
    }
}
