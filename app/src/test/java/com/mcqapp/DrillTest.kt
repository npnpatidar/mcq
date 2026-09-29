package com.mcqapp

import com.mcqapp.domain.Drill
import com.mcqapp.domain.Question
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrillTest {

    private val questions = (1..10).map {
        Question(
            id = "q$it",
            categoryId = "c",
            text = "Q$it",
            options = emptyList(),
            correctOptionIds = emptySet()
        )
    }

    @Test
    fun sampleTakesSubsetDeterministically() {
        val first = Drill.sample(questions, 5, 42L)
        val second = Drill.sample(questions, 5, 42L)
        assertEquals(5, first.size)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertTrue(first.map { it.id }.all { id -> questions.any { it.id == id } })
    }

    @Test
    fun differentSeedsDiffer() {
        val orders = (1L..5L).map { Drill.sample(questions, 5, it).map { q -> q.id } }.toSet()
        assertTrue(orders.size > 1)
    }

    @Test
    fun nonPositiveOrOversizeCountKeepsAll() {
        assertEquals(questions, Drill.sample(questions, 0, 1L))
        assertEquals(questions, Drill.sample(questions, -3, 1L))
        assertEquals(questions, Drill.sample(questions, 10, 1L))
        assertEquals(questions, Drill.sample(questions, 99, 1L))
    }
}
