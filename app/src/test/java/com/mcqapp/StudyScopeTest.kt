package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.StudyCard
import com.mcqapp.domain.StudyReason
import com.mcqapp.domain.StudyScope
import com.mcqapp.domain.StudyScopeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM coverage for the tag/mistake study scope model. */
class StudyScopeTest {

    private fun card(id: String) = StudyCard(id, StudyReason.NEW, CardState(id))

    @Test
    fun scopeIsDecodedFromRouteArguments() {
        val scope = StudyScope.fromRoute(listOf("c1", "c2"), "Physics", false)
        assertEquals(StudyScopeKind.TAG, scope.kind)
        assertEquals("physics", scope.tag)
        assertEquals(listOf("c1", "c2"), scope.categoryIds)
    }

    @Test
    fun mistakesWinOverTagAndCategoryWhenBothArePresent() {
        val scope = StudyScope.fromRoute(listOf("c1"), "x", true)
        assertEquals(StudyScopeKind.MISTAKES, scope.kind)
        assertTrue(scope.isRestricted)
    }

    @Test
    fun wholePaperIsUnrestricted() {
        assertFalse(StudyScope.wholePaper().isRestricted)
        assertEquals(StudyScopeKind.WHOLE, StudyScope.fromRoute(emptyList(), "", false).kind)
    }

    @Test
    fun filterQueueKeepsOnlyAllowedQuestionsInOrder() {
        val queue = listOf(card("a"), card("b"), card("c"))
        assertEquals(
            listOf("a", "c"),
            StudyScope.filterQueue(queue, setOf("a", "c")).map { it.questionId }
        )
    }
}
