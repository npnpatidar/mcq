package com.mcqapp

import com.mcqapp.domain.CategoryCheckState
import com.mcqapp.domain.CategoryCheckState.CHECKED
import com.mcqapp.domain.CategoryCheckState.INDETERMINATE
import com.mcqapp.domain.CategoryCheckState.UNCHECKED
import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.CategorySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cascading selection model the drawer renders and a scoped session serves.
 * Pure JVM: the model has no Android types, so it runs on every host.
 *
 * Every reachable `on` set holds the invariant that a node with children is on
 * only when every child is on; a partial branch is never on. That is what
 * makes unticking one child stick under a ticked ancestor.
 */
class CategorySelectionTest {

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

    private val allIds = setOf("A", "A1", "A1a", "A2", "B")

    @Test
    fun tickingAParentTicksEveryDescendant() {
        val nodes = forest()
        val on = CategorySelection.toggle(nodes, emptySet(), "A")
        assertEquals(setOf("A", "A1", "A1a", "A2"), on)
        assertEquals(CHECKED, CategorySelection.state(nodes, on, "A"))
    }

    @Test
    fun untickingAParentClearsTheWholeSubtree() {
        val nodes = forest()
        val once = CategorySelection.toggle(nodes, emptySet(), "A")
        val twice = CategorySelection.toggle(nodes, once, "A")
        assertEquals(emptySet<String>(), twice)
    }

    @Test
    fun untickingOneChildMakesTheParentIndeterminate() {
        val nodes = forest()
        val ticked = CategorySelection.toggle(nodes, emptySet(), "A")
        val on = CategorySelection.toggle(nodes, ticked, "A2")
        // The child stays off and the ancestor loses its own on state: it is
        // not re-asserted from above, which was revision 1's defect.
        assertEquals(setOf("A1", "A1a"), on)
        assertFalse("A" in on)
        assertEquals(INDETERMINATE, CategorySelection.state(nodes, on, "A"))
        assertEquals(UNCHECKED, CategorySelection.state(nodes, on, "A2"))
    }

    @Test
    fun tickingTheLastMissingChildMakesTheParentChecked() {
        val nodes = forest()
        val partial = CategorySelection.toggle(
            nodes,
            CategorySelection.toggle(nodes, emptySet(), "A"),
            "A2"
        )
        assertEquals(INDETERMINATE, CategorySelection.state(nodes, partial, "A"))
        val on = CategorySelection.toggle(nodes, partial, "A2")
        assertEquals(setOf("A", "A1", "A1a", "A2"), on)
        assertEquals(CHECKED, CategorySelection.state(nodes, on, "A"))
    }

    @Test
    fun tickingEveryLeafChecksTheAncestors() {
        val nodes = forest()
        var on = CategorySelection.toggle(nodes, emptySet(), "A1a")
        assertEquals(setOf("A1", "A1a"), on)
        assertEquals(INDETERMINATE, CategorySelection.state(nodes, on, "A"))
        on = CategorySelection.toggle(nodes, on, "A2")
        assertEquals(setOf("A", "A1", "A1a", "A2"), on)
        assertEquals(CHECKED, CategorySelection.state(nodes, on, "A"))
        assertEquals(
            listOf("A", "A1", "A1a", "A2"),
            CategorySelection.orderedIds(nodes, CategorySelection.tickedIds(nodes, on))
        )
    }

    @Test
    fun tickingAnIndeterminateParentChecksEverything() {
        val nodes = forest()
        val partial = CategorySelection.toggle(
            nodes,
            CategorySelection.toggle(nodes, emptySet(), "A"),
            "A2"
        )
        assertEquals(INDETERMINATE, CategorySelection.state(nodes, partial, "A"))
        val on = CategorySelection.toggle(nodes, partial, "A")
        assertEquals(setOf("A", "A1", "A1a", "A2"), on)
        assertEquals(CHECKED, CategorySelection.state(nodes, on, "A"))
    }

    @Test
    fun anIndeterminateBranchContributesOnlyItsOnChildren() {
        val nodes = forest()
        val partial = CategorySelection.toggle(
            nodes,
            CategorySelection.toggle(nodes, emptySet(), "A"),
            "A2"
        )
        assertEquals(INDETERMINATE, CategorySelection.state(nodes, partial, "A"))
        val ticked = CategorySelection.tickedIds(nodes, partial)
        assertEquals(listOf("A1"), ticked)
        assertEquals(listOf("A1", "A1a"), CategorySelection.orderedIds(nodes, ticked))
    }

    @Test
    fun toggleIgnoresAnIdThatIsNotInTheTree() {
        val nodes = forest()
        val on = setOf("B")
        assertEquals(on, CategorySelection.toggle(nodes, on, "deleted"))
    }

    @Test
    fun orderedIdsFollowsPreOrder() {
        val nodes = forest()
        val ticked = CategorySelection.tickedIds(nodes, allIds)
        // The root's subtree already covers its children, so the maximal on
        // nodes are the two roots.
        assertEquals(listOf("A", "B"), ticked)
        assertEquals(
            listOf("A", "A1", "A1a", "A2", "B"),
            CategorySelection.orderedIds(nodes, ticked)
        )
    }

    @Test
    fun aForestWithSeveralRootsExpandsIndependently() {
        val nodes = forest()
        val on = CategorySelection.toggle(nodes, emptySet(), "B")
        assertEquals(setOf("B"), on)
        assertEquals(UNCHECKED, CategorySelection.state(nodes, on, "A"))
        assertEquals(listOf("B"), CategorySelection.orderedIds(nodes, CategorySelection.tickedIds(nodes, on)))
    }

    @Test
    fun deepNestingIsFullyExpanded() {
        val chain = node("c1", node("c2", node("c3", node("c4", node("c5")))))
        val nodes = listOf(chain)
        val on = CategorySelection.toggle(nodes, emptySet(), "c1")
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), on.toList().sorted())
        assertEquals(CHECKED, CategorySelection.state(nodes, on, "c5"))
        assertEquals(
            listOf("c1", "c2", "c3", "c4", "c5"),
            CategorySelection.orderedIds(nodes, CategorySelection.tickedIds(nodes, on))
        )
    }

    @Test
    fun stateOfAnEmptyPaperIsUnchecked() {
        val nodes = emptyList<CategoryNode>()
        assertEquals(UNCHECKED, CategorySelection.state(nodes, emptySet(), "anything"))
        assertTrue(CategorySelection.states(nodes, emptySet()).isEmpty())
        assertTrue(CategorySelection.tickedIds(nodes, emptySet()).isEmpty())
        assertTrue(CategorySelection.orderedIds(nodes, emptySet()).isEmpty())
    }

    @Test
    fun statesDerivesFromTheStoredOnSet() {
        val nodes = forest()
        val reachable = listOf(
            emptySet(),
            CategorySelection.toggle(nodes, emptySet(), "A"),
            CategorySelection.toggle(nodes, emptySet(), "B"),
            CategorySelection.toggle(
                nodes,
                CategorySelection.toggle(nodes, emptySet(), "A"),
                "A2"
            )
        )
        for (on in reachable) {
            val states = CategorySelection.states(nodes, on)
            assertEquals("on=$on", allIds, states.keys)
            for (id in allIds) {
                assertEquals(
                    "on=$on id=$id",
                    CategorySelection.state(nodes, on, id),
                    states[id]
                )
            }
        }
    }

    @Test
    fun statesOnATenThousandNodeForestIsCorrect() {
        // Wide, not deep: the recursion follows nesting, not sibling count.
        val nodes = (0 until 10_000).map { node("c$it") }
        val on = CategorySelection.toggle(nodes, emptySet(), "c4999")
        val states = CategorySelection.states(nodes, on)
        assertEquals(10_000, states.size)
        assertEquals(1, states.values.count { it == CHECKED })
        assertEquals(9_999, states.values.count { it == UNCHECKED })
        assertEquals(CHECKED, states["c4999"])
        assertEquals(UNCHECKED, states["c0"])
        assertEquals(listOf("c4999"), CategorySelection.orderedIds(nodes, CategorySelection.tickedIds(nodes, on)))
    }

    @Test
    fun reExpandingTheTickedIdsReproducesTheScope() {
        val nodes = forest()
        val reachable = listOf(
            emptySet(),
            CategorySelection.toggle(nodes, emptySet(), "A"),
            CategorySelection.toggle(nodes, emptySet(), "B"),
            CategorySelection.toggle(
                nodes,
                CategorySelection.toggle(nodes, emptySet(), "A"),
                "A2"
            ),
            allIds
        )
        for (on in reachable) {
            val ticked = CategorySelection.tickedIds(nodes, on)
            assertEquals(
                "on=$on ticked=$ticked",
                on,
                CategorySelection.orderedIds(nodes, ticked).toSet()
            )
        }
    }
}
