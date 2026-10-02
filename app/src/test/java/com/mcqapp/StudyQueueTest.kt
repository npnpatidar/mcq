package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.Study
import com.mcqapp.domain.StudyReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library's "N tricky" button used to run the identical handler as
 * "Study", so the label promised the user's problem questions while the
 * ordinary due+new queue was built.
 */
class StudyQueueTest {

    private fun card(id: String, state: CardState, reason: StudyReason) =
        com.mcqapp.domain.StudyCard(id, reason, state)

    private val fresh = CardState(questionId = "new")
    private val leech = CardState(questionId = "leech", reps = 9, lapses = 8, leech = true)
    private val normal = CardState(questionId = "normal", reps = 3)

    @Test
    fun onlyLeechesKeepsTheLeechCards() {
        val cards = listOf(
            card("a", leech, StudyReason.LEECH),
            card("b", normal, StudyReason.DUE),
            card("c", fresh, StudyReason.NEW)
        )
        assertEquals(listOf("a"), Study.onlyLeeches(cards).map { it.questionId })
    }

    @Test
    fun aCardFlaggedLeechIsKeptWhateverItsReason() {
        // A leech that is also due must still surface in a tricky session.
        val dueLeech = card("a", leech, StudyReason.DUE)
        assertEquals(listOf("a"), Study.onlyLeeches(listOf(dueLeech)).map { it.questionId })
    }

    @Test
    fun noLeechesMeansAnEmptySession() {
        val cards = listOf(card("b", normal, StudyReason.DUE), card("c", fresh, StudyReason.NEW))
        assertTrue(Study.onlyLeeches(cards).isEmpty())
    }

    @Test
    fun filteringAnEmptyQueueIsEmpty() {
        assertTrue(Study.onlyLeeches(emptyList()).isEmpty())
    }
}
