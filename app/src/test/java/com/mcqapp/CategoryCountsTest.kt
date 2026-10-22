package com.mcqapp

import com.mcqapp.domain.CategoryCounts
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.CategorySelection
import com.mcqapp.domain.rollUpCategoryCounts
import com.mcqapp.domain.totalCategoryCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-category rollup the paper card and the drawer render (D3, §5 of
 * `category_card_counts.md`). Pure JVM: the rollup is a pure function over
 * `CategoryNode`, so it runs on every host. The SQL it rolls up is pinned by
 * `CategoryCountsQueryTest`, which is CI-authoritative.
 */
class CategoryCountsTest {

    private fun counts(due: Int = 0, fresh: Int = 0, leeches: Int = 0) =
        CategoryCounts(due = due, fresh = fresh, leeches = leeches)

    private fun node(id: String, vararg children: CategoryNode): CategoryNode =
        CategoryNode(
            id = id,
            paperId = "p1",
            title = "Title $id",
            parentId = null,
            children = children.toList()
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

    // --- rollup ---

    @Test
    fun aLeafKeepsItsOwnValues() {
        val rolled = rollUpCategoryCounts(forest(), mapOf("A1a" to counts(due = 2, fresh = 1)))
        assertEquals(counts(due = 2, fresh = 1), rolled["A1a"])
    }

    @Test
    fun aParentSumsItsOwnCountsAndItsChildrensTotals() {
        val own = mapOf(
            "A" to counts(due = 1),
            "A1" to counts(fresh = 2),
            "A1a" to counts(due = 3, leeches = 4),
            "A2" to counts(due = 5)
        )
        val rolled = rollUpCategoryCounts(forest(), own)
        assertEquals(counts(due = 3, fresh = 2, leeches = 4), rolled["A1"])
        assertEquals(counts(due = 9, fresh = 2, leeches = 4), rolled["A"])
    }

    @Test
    fun twoRootsStayDisjoint() {
        val own = mapOf(
            "A1a" to counts(due = 3),
            "B" to counts(due = 7, leeches = 1)
        )
        val rolled = rollUpCategoryCounts(forest(), own)
        assertEquals(counts(due = 3), rolled["A"])
        assertEquals(counts(due = 7, leeches = 1), rolled["B"])
    }

    @Test
    fun anEmptyForestRollsUpToNothing() {
        assertTrue(rollUpCategoryCounts(emptyList(), mapOf("A" to counts(due = 1))).isEmpty())
        assertEquals(CategoryCounts(), totalCategoryCounts(emptyMap(), listOf("A", "B")))
    }

    @Test
    fun aNodeAbsentFromOwnContributesZeroButIsStillPresent() {
        val rolled = rollUpCategoryCounts(forest(), mapOf("A1a" to counts(due = 3)))
        assertEquals(counts(due = 3), rolled["A1"])
        assertEquals(counts(due = 3), rolled["A"])
        assertEquals(CategoryCounts(), rolled["B"])
    }

    @Test
    fun everyForestNodeIsInTheResult() {
        assertEquals(
            setOf("A", "A1", "A1a", "A2", "B"),
            rollUpCategoryCounts(forest(), emptyMap()).keys
        )
    }

    @Test
    fun theResultIsInPreOrder() {
        assertEquals(
            listOf("A", "A1", "A1a", "A2", "B"),
            rollUpCategoryCounts(forest(), emptyMap()).keys.toList()
        )
    }

    // --- the scope total ---

    @Test
    fun theScopeTotalSumsOnlyTheMaximalTickedBranches() {
        val own = mapOf(
            "A" to counts(due = 1),
            "A1" to counts(due = 2),
            "A1a" to counts(due = 4),
            "A2" to counts(due = 8),
            "B" to counts(due = 16)
        )
        val nodes = forest()
        val rolled = rollUpCategoryCounts(nodes, own)

        // A ticked with its whole subtree is one maximal branch: its rolled
        // total, counted once, not per descendant.
        val whole = CategorySelection.toggle(nodes, emptySet(), "A")
        assertEquals(listOf("A"), CategorySelection.tickedIds(nodes, whole))
        assertEquals(counts(due = 15), totalCategoryCounts(rolled, CategorySelection.tickedIds(nodes, whole)))

        // A2 unticked under a ticked A: A is indeterminate and leaves the
        // scope, so the maximal branch is A1 (A1a's parent), not A or A1a.
        val partial = CategorySelection.toggle(nodes, whole, "A2")
        assertEquals(listOf("A1"), CategorySelection.tickedIds(nodes, partial))
        assertEquals(counts(due = 6), totalCategoryCounts(rolled, CategorySelection.tickedIds(nodes, partial)))
    }

    @Test
    fun duplicateTickedIdsAreCountedOnce() {
        val rolled = rollUpCategoryCounts(
            forest(),
            mapOf("A" to counts(due = 1), "B" to counts(due = 2))
        )
        assertEquals(
            totalCategoryCounts(rolled, listOf("A", "B")),
            totalCategoryCounts(rolled, listOf("A", "B", "A", "B"))
        )
    }

    @Test
    fun anUnknownTickedIdContributesZero() {
        val rolled = rollUpCategoryCounts(forest(), mapOf("A" to counts(due = 1)))
        assertEquals(counts(due = 1), totalCategoryCounts(rolled, listOf("A", "gone")))
        assertEquals(CategoryCounts(), totalCategoryCounts(rolled, listOf("gone")))
    }

    // --- the label's inputs (§9.1) ---

    @Test
    fun theLabelSeesDuePlusNewOrEitherAlone() {
        val rolled = rollUpCategoryCounts(
            forest(),
            mapOf("A" to counts(due = 7, fresh = 3), "B" to counts(leeches = 2))
        )
        assertEquals(counts(due = 7, fresh = 3), totalCategoryCounts(rolled, listOf("A")))
        assertEquals(counts(leeches = 2), totalCategoryCounts(rolled, listOf("B")))
        assertEquals(counts(due = 7, fresh = 3, leeches = 2), totalCategoryCounts(rolled, listOf("A", "B")))
    }

    // --- the value object ---

    @Test
    fun emptyMeansEveryPoolIsZero() {
        assertTrue(CategoryCounts().isEmpty)
        assertFalse(counts(due = 1).isEmpty)
        assertFalse(counts(fresh = 1).isEmpty)
        assertFalse(counts(leeches = 1).isEmpty)
    }
}
