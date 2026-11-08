package com.mcqapp

import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.ScopeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparability key has to be stable across tick orders, process restarts
 * and the UI's parent/child representation of the same selection. The frozen
 * fixture below fails loudly if the algorithm changes, because a silent change
 * would orphan every trend stored under the old key.
 */
class ScopeKeyTest {

    private fun node(id: String, vararg children: CategoryNode): CategoryNode =
        CategoryNode(
            id = id,
            paperId = "p1",
            title = "Title $id",
            parentId = null,
            children = children.toList(),
            questionCount = 1
        )

    /**
     * ```text
     * A            B
     * ├─ A1
     * │   └─ A1a
     * └─ A2
     * ```
     */
    private fun forest(): List<CategoryNode> = listOf(
        node("A", node("A1", node("A1a")), node("A2")),
        node("B")
    )

    private fun key(vararg ticked: String): String = ScopeKey.of(forest(), ticked.toList())

    @Test
    fun theTickOrderDoesNotChangeTheKey() {
        assertEquals(key("A", "B"), key("B", "A"))
    }

    @Test
    fun aParentTickAndItsChildTicksProduceTheSameKey() {
        val nodes = forest()
        val parentOn = CategorySelection.toggle(nodes, emptySet(), "A")
        val childrenOn = CategorySelection.toggle(
            nodes,
            CategorySelection.toggle(nodes, emptySet(), "A1"),
            "A2"
        )
        // The UI keeps an ancestor on when every child is on, so both states
        // expand to the same maximal tick.
        assertEquals(parentOn, childrenOn)
        assertEquals(
            ScopeKey.of(nodes, CategorySelection.tickedIds(nodes, parentOn)),
            ScopeKey.of(nodes, CategorySelection.tickedIds(nodes, childrenOn))
        )
        // Passing the parent's whole subtree is the same expansion, not four
        // separate scopes.
        assertEquals(key("A"), key("A", "A1", "A1a", "A2"))
    }

    @Test
    fun duplicateTicksAreRemoved() {
        assertEquals(key("A", "B"), key("B", "B", "A", "B"))
    }

    @Test
    fun theWholePaperIsNotAnEmptyCategoryList() {
        val whole = ScopeKey.wholePaper("p1")
        assertEquals("paper:p1", whole)
        assertFalse(whole.isEmpty())
        assertNotEquals(whole, key())
        assertNotEquals(ScopeKey.wholePaper("p1"), ScopeKey.wholePaper("p2"))
        // An empty category list is still a well-formed key, just a different
        // one: it is not silently promoted to "whole paper".
        assertEquals(ScopeKey.ofExpanded(emptyList()), key())
    }

    @Test
    fun differentScopesProduceDifferentKeys() {
        assertNotEquals(key("A"), key("B"))
        assertNotEquals(key("A"), key("A", "B"))
    }

    @Test
    fun theKeyIsFrozen() {
        // SHA-256 of "A\u001fA1\u001fA1a\u001fA2\u001fB". If this changes, every
        // attempt stored under the old key becomes a different trend.
        assertEquals(
            "d8a74b41394712bac2c21cdcaeba80b4722de0938a03718fd3f06a58410c5ec2",
            key("A", "B")
        )
    }

    @Test
    fun theKeyIsAStableSha256Hex() {
        val value = key("A", "B")
        assertEquals(64, value.length)
        assertTrue(value.all { it in "0123456789abcdef" })
        assertEquals(value, key("A", "B"))
        // The empty expansion is still a hash, not the empty string.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ScopeKey.ofExpanded(emptyList())
        )
    }
}
