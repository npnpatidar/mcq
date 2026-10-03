package com.mcqapp

import com.mcqapp.data.io.SelectionExport
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exporting a selection from Browse must not quietly flatten it. The interesting
 * cases are the ones a naive copy drops: an option that is a formula or a
 * picture, and the owning category that says where the question came from.
 *
 * [bookmarkExportKeepsAnOptionThatIsAFormula] is a regression test: the
 * bookmark exporter used `OptionDto(id, text, image)`, whose convenience
 * constructor rebuilds one plain-text element, so a formula in an option was
 * exported as bare text with no warning anywhere.
 */
class SelectionExportTest {

    private fun question(
        id: String,
        categoryId: String,
        formula: String = "",
        image: String? = null
    ) = Question(
        id = id,
        categoryId = categoryId,
        elements = listOf(ContentElement.TextElement("Question $id")),
        options = listOf(
            QuestionOption(
                id = "a",
                elements = if (formula.isEmpty()) {
                    listOf(ContentElement.TextElement("first"))
                } else {
                    listOf(ContentElement.MathElement(formula))
                }
            ),
            QuestionOption(
                id = "b",
                elements = listOf(ContentElement.TextElement("second")),
                image = image
            )
        ),
        correctOptionIds = setOf("a"),
        difficulty = Difficulty.MEDIUM,
        marks = 2.0,
        tags = listOf("tag")
    )

    @Test
    fun groupsSelectedQuestionsUnderTheirOwnCategory() {
        val dto = SelectionExport.paperDto(
            title = "Demo (3 questions)",
            questions = listOf(
                question("q1", "cat-science"),
                question("q2", "cat-science"),
                question("q3", "cat-history")
            ),
            categoryTitleById = mapOf("cat-science" to "Science", "cat-history" to "History")
        )
        assertEquals(listOf("History", "Science"), dto.categories.map { it.title })
        assertEquals(2, dto.categories.first { it.title == "Science" }.questions.size)
        assertEquals(1, dto.categories.first { it.title == "History" }.questions.size)
        assertEquals(3, dto.categories.sumOf { it.questions.size })
    }

    @Test
    fun unknownCategoryFallsBackRatherThanVanishing() {
        val dto = SelectionExport.paperDto(
            title = "Demo",
            questions = listOf(question("q1", "cat-gone")),
            categoryTitleById = emptyMap()
        )
        assertEquals(SelectionExport.FALLBACK_CATEGORY, dto.categories.single().title)
        assertEquals(1, dto.categories.single().questions.size)
    }

    @Test
    fun bookmarkExportKeepsAnOptionThatIsAFormula() {
        val dto = com.mcqapp.data.io.BookmarkExport.paperDto(
            questions = listOf(question("q1", "cat", formula = "<math><mi>x</mi></math>")),
            paperTitleByCategoryId = mapOf("cat" to "Paper")
        )
        val option = dto.categories.single().questions.single().options.first { it.id == "a" }
        assertEquals(
            "the bookmark exporter flattened the option to text",
            listOf(ContentElement.MathElement("<math><mi>x</mi></math>")),
            option.elements
        )
    }

    @Test
    fun anOptionThatIsAFormulaKeepsItsElements() {
        val dto = SelectionExport.paperDto(
            title = "Demo",
            questions = listOf(question("q1", "cat", formula = "<math><mi>x</mi></math>")),
            categoryTitleById = mapOf("cat" to "Cat")
        )
        val option = dto.categories.single().questions.single().options.first { it.id == "a" }
        assertEquals(1, option.elements.size)
        assertTrue(
            "expected the formula to survive, got ${option.elements}",
            option.elements.single() is ContentElement.MathElement
        )
    }

    @Test
    fun everyOtherFieldSurvivesTheCopy() {
        val dto = SelectionExport.paperDto(
            title = "Demo",
            questions = listOf(
                question("q1", "cat", image = "data:image/png;base64,AAAA")
            ),
            categoryTitleById = mapOf("cat" to "Cat")
        )
        val copied = dto.categories.single().questions.single()
        assertEquals("q1", copied.id)
        assertEquals("Question q1", copied.text)
        assertEquals(listOf("a"), copied.correctOptionIds)
        assertEquals(2.0, copied.marks, 0.0001)
        assertEquals(listOf("tag"), copied.tags)
        assertEquals("data:image/png;base64,AAAA", copied.options.first { it.id == "b" }.image)
    }
}
