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
    fun aCountWithinThePaperIsAccepted() {
        assertTrue(Drill.canSample(10, 10))
        assertTrue(Drill.canSample(4, 10))
        assertEquals(Drill.CountCheck.Ok, Drill.checkCount(4, 10))
    }

    @Test
    fun askingForMoreThanThePaperHoldsIsRefused() {
        // The UI must refuse rather than quietly hand back a smaller drill, which
        // is what Drill.sample does when the count exceeds the set size.
        assertEquals(
            Drill.CountCheck.TooManyForPaper(999, 10),
            Drill.checkCount(999, questions.size)
        )
        assertTrue(!Drill.canSample(999, questions.size))
        assertTrue(Drill.sample(questions, 999, 1L).size < 999)
    }

    @Test
    fun exactlyThePaperSizeIsAccepted() {
        assertTrue(Drill.canSample(10, 10))
        assertEquals(Drill.CountCheck.Ok, Drill.checkCount(10, 10))
    }

    @Test
    fun anEmptyPaperCannotBeDrilled() {
        assertEquals(Drill.CountCheck.NoQuestionsAvailable, Drill.checkCount(10, 0))
        assertTrue(!Drill.canSample(10, 0))
    }

    @Test
    fun nonPositiveOrOversizeCountKeepsAll() {
        assertEquals(questions, Drill.sample(questions, 0, 1L))
        assertEquals(questions, Drill.sample(questions, -3, 1L))
        assertEquals(questions, Drill.sample(questions, 10, 1L))
        assertEquals(questions, Drill.sample(questions, 99, 1L))
    }
}
