package com.mcqapp

import com.mcqapp.domain.CategoryNode
import com.mcqapp.ui.library.CategoryTreePager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM coverage for bounding large expanded category trees. */
class CategoryTreePagerTest {

    private fun node(id: String, children: List<CategoryNode> = emptyList()) = CategoryNode(
        id = id,
        paperId = "p",
        title = id,
        parentId = null,
        children = children
    )

    @Test
    fun capsSiblingsAndReportsHiddenCountOnLastVisibleRow() {
        val nodes = (1..20).map { node("c$it") }
        val page = CategoryTreePager.page(nodes, limit = 6)
        assertEquals(6, page.size)
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5", "c6"), page.map { it.node.id })
        assertEquals(14, page.last().hiddenAfter)
        assertTrue(page.take(5).all { it.hiddenAfter == 0 })
    }

    @Test
    fun expandingAParentRevealsItsChildrenAtTheSameBound() {
        val parent = node("p", (1..10).map { node("child$it") })
        val collapsed = CategoryTreePager.page(listOf(parent), limit = 3)
        assertEquals(1, collapsed.size)
        val expanded = CategoryTreePager.page(listOf(parent), expanded = setOf("p"), limit = 3)
        assertEquals(4, expanded.size)
        assertEquals("child1", expanded[1].node.id)
        assertEquals(1, expanded[1].depth)
        assertEquals(7, expanded.last().hiddenAfter)
    }

    /**
     * The pickers pass every id as [CategoryTreePager.page]'s `expanded` set,
     * so a nested child row always exists. Passing only the "show N more"
     * overflow ids used to fold every branch shut and made child categories
     * unselectable.
     */
    @Test
    fun everyIdExpandedKeepsNestedChildrenVisible() {
        val tree = listOf(node("root", listOf(node("mid", listOf(node("leaf"))))))
        val allIds = CategoryTreePager.allIds(tree)
        val page = CategoryTreePager.page(tree, expanded = allIds)
        assertEquals(listOf("root", "mid", "leaf"), page.map { it.node.id })
        assertEquals(listOf(0, 1, 2), page.map { it.depth })
        assertTrue(page.all { it.hiddenAfter == 0 })
    }

    @Test
    fun revealedBoundsOnlyTheOverflowNotTheChildren() {
        val tree = listOf(node("p", (1..10).map { node("child$it") }))
        val allIds = CategoryTreePager.allIds(tree)
        val bounded = CategoryTreePager.page(tree, expanded = allIds, limit = 6)
        assertEquals(7, bounded.size) // parent + six visible children
        assertEquals(4, bounded.last().hiddenAfter)
        val revealed = CategoryTreePager.page(
            tree, expanded = allIds, revealed = setOf("child6"), limit = 6
        )
        assertEquals(11, revealed.size)
        assertEquals(0, revealed.last().hiddenAfter)
    }

    @Test
    fun smallTreesAreUnchangedAndOrderIsPreserved() {
        val nodes = listOf(node("a"), node("b"))
        val page = CategoryTreePager.page(nodes, limit = 6)
        assertEquals(listOf("a", "b"), page.map { it.node.id })
        assertTrue(page.all { it.hiddenAfter == 0 })
    }
}
