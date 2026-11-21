package com.mcqapp

import com.mcqapp.domain.BlueprintQuotas
import com.mcqapp.domain.CategoryNode
import com.mcqapp.ui.library.blueprintPreviewViews
import com.mcqapp.ui.library.testableCategoryTree
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The blueprint editor's preview must pool the questions a run can actually
 * draw. Its `pool` came from `CategoryNode.totalQuestionCount` (raw, cards
 * included), while Start and the saved blueprint pool the testable count — so
 * a card-heavy section promised a quota the run could not fill.
 *
 * Pure JVM: these are the same view/parts/quota functions the editor and the
 * setup sheet use.
 */
class BlueprintEditorPoolTest {

    private fun node(
        id: String,
        raw: Int,
        children: List<CategoryNode> = emptyList()
    ) = CategoryNode(
        id = id,
        paperId = "p1",
        title = id,
        parentId = null,
        children = children,
        questionCount = raw
    )

    @Test
    fun aSectionWithTwoMcqsAndThreeCardsPreviewsAPoolAndQuotaOfTwo() {
        // 2 multiple-choice + 3 flashcards in one section; a second section
        // holds flashcards only.
        val categories = listOf(node("mixed", raw = 5), node("cards", raw = 3))
        val tree = testableCategoryTree(categories, mapOf("mixed" to 2, "cards" to 0))
        assertEquals(listOf(2, 0), tree.map { it.questionCount })

        val views = blueprintPreviewViews(tree, mapOf("mixed" to 1, "cards" to 1))
        assertEquals(2, views.first { it.categoryId == "mixed" }.pool)
        assertEquals(0, views.first { it.categoryId == "cards" }.pool)

        // Asking for 4: the mixed section is capped at its testable pool, and
        // the card-only section supplies nothing.
        val allocation = BlueprintQuotas.allocate(views, 4)
        assertEquals(2, allocation.perTerminal["mixed"])
        assertEquals(0, allocation.perTerminal["cards"] ?: 0)
        assertEquals(2, allocation.achievable)
    }

    @Test
    fun aTerminalSectionRollsUpOnlyItsTestableSubtree() {
        val categories = listOf(
            node(
                "parent",
                raw = 10,
                children = listOf(node("child", raw = 3), node("other", raw = 2))
            )
        )
        val tree = testableCategoryTree(
            categories,
            mapOf("parent" to 1, "child" to 3, "other" to 1)
        )
        // The parent has no included child, so its terminal pool is the whole
        // subtree — testable members only (1 + 3 + 1), not the raw 10.
        val views = blueprintPreviewViews(tree, mapOf("parent" to 1))
        assertEquals(5, views.single().pool)
    }

    @Test
    fun aCategoryWithoutATestableCountReadsAsZeroAndNeverRaw() {
        val tree = testableCategoryTree(listOf(node("unread", raw = 50)), emptyMap())
        assertEquals(0, tree.single().questionCount)
        assertEquals(0, blueprintPreviewViews(tree, mapOf("unread" to 1)).single().pool)
    }
}
