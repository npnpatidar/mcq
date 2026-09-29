package com.mcqapp

import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.domain.Shuffle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleTest {

    private fun question(
        id: String,
        options: List<String> = listOf("a", "b", "c", "d"),
        correct: Set<String> = setOf("a")
    ) = Question(
        id = id,
        categoryId = "c",
        text = "Q $id",
        options = options.map { QuestionOption(id = "$id-$it", text = it) },
        correctOptionIds = correct.map { "$id-$it" }.toSet()
    )

    @Test
    fun disabledFlagsReturnOriginalOrder() {
        val questions = (1..8).map { question("q$it") }
        val out = Shuffle.shuffleAttempt(questions, 42L, false, false)
        assertEquals(questions.map { it.id }, out.map { it.id })
        assertEquals(
            questions.flatMap { q -> q.options.map { it.id } },
            out.flatMap { q -> q.options.map { it.id } }
        )
    }

    @Test
    fun sameSeedReproducesFullLayout() {
        val questions = (1..8).map { question("q$it") }
        val first = Shuffle.shuffleAttempt(questions, 1234L, true, true)
        val second = Shuffle.shuffleAttempt(questions, 1234L, true, true)
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(
            first.flatMap { q -> q.options.map { it.id } },
            second.flatMap { q -> q.options.map { it.id } }
        )
    }

    @Test
    fun differentSeedsChangeOrder() {
        val questions = (1..8).map { question("q$it") }
        val orders = (1L..10L).map { seed ->
            Shuffle.shuffleAttempt(questions, seed, true, true).map { it.id }
        }.toSet()
        assertTrue("expected distinct orders across seeds, got $orders", orders.size > 1)
    }

    @Test
    fun shufflePreservesIdsAndAnswerKeys() {
        val questions = (1..8).map { question("q$it") } +
            question("q9", correct = emptySet())
        val out = Shuffle.shuffleAttempt(questions, 7L, true, true)
        assertEquals(questions.map { it.id }.toSet(), out.map { it.id }.toSet())
        for (q in out) {
            val optionIds = q.options.map { it.id }.toSet()
            assertEquals("option set changed for ${q.id}", 4, optionIds.size)
            assertTrue(
                "answer key broken for ${q.id}",
                q.correctOptionIds.all { it in optionIds }
            )
        }
        assertTrue(out.single { it.id == "q9" }.correctOptionIds.isEmpty())
    }

    @Test
    fun optionOnlyShuffleKeepsQuestionOrder() {
        val questions = (1..8).map { question("q$it") }
        val out = Shuffle.shuffleAttempt(questions, 99L, false, true)
        assertEquals(questions.map { it.id }, out.map { it.id })
        val optionOrders = out.map { q -> q.options.map { it.id } }.toSet()
        assertTrue("expected options to be reordered", optionOrders.size > 1)
    }

    @Test
    fun questionOnlyShuffleKeepsOptionOrder() {
        val questions = (1..8).map { question("q$it") }
        val out = Shuffle.shuffleAttempt(questions, 5L, true, false)
        assertNotEquals(questions.map { it.id }, out.map { it.id })
        for ((original, shuffled) in questions.map { it.id }.zip(out.map { it.id })) {
            val origOptions = questions.single { it.id == shuffled }.options.map { it.id }
            val newOptions = out.single { it.id == shuffled }.options.map { it.id }
            assertEquals("options moved for $original", origOptions, newOptions)
        }
    }
}
