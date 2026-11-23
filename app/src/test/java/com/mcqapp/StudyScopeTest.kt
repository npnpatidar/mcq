package com.mcqapp

import com.mcqapp.domain.CardState
import com.mcqapp.domain.Question
import com.mcqapp.domain.StudyCard
import com.mcqapp.domain.StudyReason
import com.mcqapp.domain.StudyScope
import com.mcqapp.domain.StudyScopeKind
import com.mcqapp.domain.StudyScopeMenuChoice
import com.mcqapp.domain.StudyScopeMenuSelection
import com.mcqapp.domain.mistakeIdsInScope
import com.mcqapp.domain.studyScopeMenuSelection
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

    @Test
    fun switchingOutOfADrillStartsAFreshTagOrMistakeScope() {
        // A carried `leechesOnly` used to win at load, so neither choice took
        // effect. Both must clear it; tag and mistake stay in the category.
        val tag = studyScopeMenuSelection(
            currentCategoryIds = listOf("c1", "c2"),
            choice = StudyScopeMenuChoice.TAG,
            foldedTag = "physics"
        )
        assertEquals(
            StudyScopeMenuSelection(
                categoryIds = listOf("c1", "c2"),
                tag = "physics"
            ),
            tag
        )
        val mistakes = studyScopeMenuSelection(listOf("c1"), StudyScopeMenuChoice.MISTAKES)
        assertEquals(StudyScopeMenuSelection(categoryIds = listOf("c1"), mistakes = true), mistakes)
    }

    @Test
    fun switchingToWholeFromACategoryScopeClearsEveryRestriction() {
        assertEquals(
            StudyScopeMenuSelection(),
            studyScopeMenuSelection(
                currentCategoryIds = listOf("c1", "c2"),
                choice = StudyScopeMenuChoice.WHOLE,
                foldedTag = "physics"
            )
        )
    }

    @Test
    fun mistakeIdsAreRestrictedToTheResolvedCategoryScopeInRecencyOrder() {
        fun question(id: String, categoryId: String) = Question(
            id = id,
            categoryId = categoryId,
            text = id,
            options = emptyList(),
            correctOptionIds = emptySet()
        )
        val wrong = listOf(
            question("q1", "cA"),
            question("q2", "cB"),
            question("q3", "cA")
        )
        assertEquals(listOf("q1", "q3"), mistakeIdsInScope(wrong, listOf("cA", "cChild")))
        assertEquals(listOf("q1", "q2", "q3"), mistakeIdsInScope(wrong, emptyList()))
    }
}
