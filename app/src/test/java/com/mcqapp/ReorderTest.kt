package com.mcqapp

import com.mcqapp.domain.Question
import com.mcqapp.domain.Reorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReorderTest {

    private fun question(id: String, categoryId: String) = Question(
        id = id,
        categoryId = categoryId,
        text = "Q $id",
        options = emptyList(),
        correctOptionIds = emptySet()
    )

    private val questions = listOf(
        question("q1", "c1"),
        question("q2", "c1"),
        question("q3", "c2")
    )

    @Test
    fun sameCategoryNeighboursCanSwap() {
        assertTrue(Reorder.canMove(questions, 0, +1))
        assertTrue(Reorder.canMove(questions, 1, -1))
    }

    @Test
    fun crossCategoryAndOutOfBoundsCannot() {
        assertFalse(Reorder.canMove(questions, 1, +1))
        assertFalse(Reorder.canMove(questions, 2, -1))
        assertFalse(Reorder.canMove(questions, 0, -1))
        assertFalse(Reorder.canMove(questions, 2, +1))
        assertFalse(Reorder.canMove(questions, -1, +1))
        assertFalse(Reorder.canMove(questions, 3, -1))
        assertFalse(Reorder.canMove(emptyList(), 0, +1))
    }

    @Test
    fun normalizedOrderMovesAndRenumbers() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(
            mapOf("a" to 0, "c" to 1, "b" to 2, "d" to 3),
            Reorder.normalizedOrder(ids, 1, +1)
        )
        assertEquals(
            mapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3),
            Reorder.normalizedOrder(ids, 0, -1)
        )
    }

    @Test
    fun normalizedOrderClampsAndIgnoresBadIndex() {
        val ids = listOf("a", "b", "c")
        assertEquals(
            mapOf("b" to 0, "c" to 1, "a" to 2),
            Reorder.normalizedOrder(ids, 0, +99)
        )
        assertEquals(
            mapOf("a" to 0, "b" to 1, "c" to 2),
            Reorder.normalizedOrder(ids, 9, +1)
        )
    }
}
