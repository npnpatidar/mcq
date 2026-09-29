package com.mcqapp

import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ImportWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportWarningsTest {

    private fun question(
        id: String = "q1",
        text: String = "Text?",
        options: List<OptionDto> = listOf(OptionDto("a", "A"), OptionDto("b", "B")),
        correct: List<String> = listOf("a"),
        marks: Double = 1.0,
        image: String? = null
    ) = QuestionDto(
        id = id,
        text = text,
        image = image,
        options = options,
        correctOptionIds = correct,
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
        assertTrue(messages.any { it.startsWith("No options") })
        assertTrue(messages.any { it.startsWith("No answer key") })
        assertTrue(warnings.all { it.questionId == "qx" && it.label == "Q9" })
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
        assertTrue(messages.any { it.startsWith("Only one option") })
        assertTrue(messages.any { it.contains("zzz") })
        assertTrue(messages.any { it.startsWith("Worth 0 marks") })
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
        assertTrue(byId["q-e5"]!!.any { it.message.startsWith("No options") })
        assertTrue(byId["q-o3"].orEmpty().isEmpty())
    }
}
