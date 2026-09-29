package com.mcqapp

import com.mcqapp.data.io.BookmarkExport
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkExportTest {

    private fun question(
        id: String,
        categoryId: String,
        correct: Set<String> = setOf("$id-a"),
        marks: Double = 1.0
    ) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        image = "img-$id",
        options = listOf(
            QuestionOption("$id-a", "A"),
            QuestionOption("$id-b", "B", "opt-img")
        ),
        correctOptionIds = correct,
        explanation = "Why",
        difficulty = Difficulty.HARD,
        marks = marks,
        tags = listOf("t1")
    )

    @Test
    fun groupsBySourcePaperPreservingContent() {
        val dto = BookmarkExport.paperDto(
            questions = listOf(
                question("q1", "c1", marks = 2.0),
                question("q2", "c2"),
                question("q3", "c1")
            ),
            paperTitleByCategoryId = mapOf("c1" to "Paper One", "c2" to "Paper Two")
        )
        assertEquals(BookmarkExport.PAPER_TITLE, dto.title)
        assertEquals(listOf("Paper One", "Paper Two"), dto.categories.map { it.title })
        val one = dto.categories[0]
        assertEquals(listOf("q1", "q3"), one.questions.map { it.id })
        val q1 = one.questions[0]
        assertEquals("Q q1", q1.text)
        assertEquals("img-q1", q1.image)
        assertEquals(listOf("q1-a"), q1.correctOptionIds)
        assertEquals("Why", q1.explanation)
        assertEquals("Hard", q1.difficulty)
        assertEquals(2.0, q1.marks, 0.0001)
        assertEquals(listOf("t1"), q1.tags)
        assertEquals("opt-img", q1.options[1].image)
    }

    @Test
    fun unknownCategoriesFallUnderOther() {
        val dto = BookmarkExport.paperDto(
            questions = listOf(question("q1", "gone")),
            paperTitleByCategoryId = emptyMap()
        )
        assertEquals(listOf("Other"), dto.categories.map { it.title })
        assertTrue(dto.categories.single().questions.map { it.id } == listOf("q1"))
    }

    @Test
    fun emptyBookmarksYieldEmptyPaper() {
        val dto = BookmarkExport.paperDto(emptyList(), emptyMap())
        assertTrue(dto.categories.isEmpty())
    }
}
