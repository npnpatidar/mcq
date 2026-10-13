package com.mcqapp

import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import com.mcqapp.ui.browse.applyCategoryFilter
import com.mcqapp.ui.browse.subtreeIdsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseCategoryFilterTest {

    private val questions = listOf(
        Question(
            id = "q1", categoryId = "chem", text = "Reaction of copper?",
            options = listOf(QuestionOption("a", "CuO")),
            correctOptionIds = setOf("a")
        ),
        Question(
            id = "q2", categoryId = "phys", text = "Velocity is?",
            options = listOf(QuestionOption("a", "m/s")),
            correctOptionIds = setOf("a")
        ),
        Question(
            id = "q3", categoryId = "chem", text = "Base of copper?",
            options = listOf(QuestionOption("a", "CuO2")),
            correctOptionIds = setOf("a")
        )
    )

    @Test
    fun nullCategoryKeepsTheWholeList() {
        assertEquals(questions, applyCategoryFilter(questions, null))
    }

    @Test
    fun oneCategoryKeepsOnlyItsQuestions() {
        assertEquals(
            listOf("q1", "q3"),
            applyCategoryFilter(questions, setOf("chem")).map { it.id }
        )
        assertEquals(
            listOf("q2"),
            applyCategoryFilter(questions, setOf("phys")).map { it.id }
        )
    }

    @Test
    fun unknownCategoryYieldsNothing() {
        assertEquals(
            emptyList<String>(),
            applyCategoryFilter(questions, setOf("missing")).map { it.id }
        )
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals(emptyList<Question>(), applyCategoryFilter(emptyList(), setOf("chem")))
    }

    @Test
    fun aSubtreeSetKeepsDescendantsToo() {
        // A subtree filter (a parent plus its children) keeps every member of
        // that subtree, matching what the browse dropdown resolves. The
        // result preserves the original list order (q1, q2, q3), not the
        // set's order.
        assertEquals(
            listOf("q1", "q2", "q3"),
            applyCategoryFilter(questions, setOf("chem", "phys")).map { it.id }
        )
    }

    // ---- subtreeIdsOf: the dropdown's single id -> the ids it filters ----

    private fun node(id: String, parentId: String?, children: List<CategoryNode> = emptyList()) =
        CategoryNode(id = id, paperId = "p", title = id, parentId = parentId, children = children)

    private val tree = listOf(
        node(
            "root", null, listOf(
                node("chem", "root", listOf(node("org", "chem"))),
                node("phys", "root")
            )
        )
    )

    @Test
    fun subtreeIdsOfIncludesTheRootAndAllDescendants() {
        assertEquals(setOf("root", "chem", "org", "phys"), subtreeIdsOf(flatten(tree), "root"))
        assertEquals(setOf("chem", "org"), subtreeIdsOf(flatten(tree), "chem"))
        assertEquals(setOf("org"), subtreeIdsOf(flatten(tree), "org"))
        assertEquals(setOf("phys"), subtreeIdsOf(flatten(tree), "phys"))
    }

    @Test
    fun subtreeIdsOfNullOrUnknownRootFiltersCorrectly() {
        // null root -> no filter at all; an unknown id filters to nothing
        // (a stale/deleted selection must not fall back to showing everything).
        assertNull(subtreeIdsOf(flatten(tree), null))
        assertEquals(emptySet<String>(), subtreeIdsOf(flatten(tree), "missing"))
    }

    private fun flatten(nodes: List<CategoryNode>) = buildList {
        fun walk(ns: List<CategoryNode>, depth: Int) {
            ns.forEach { add(it to depth); walk(it.children, depth + 1) }
        }
        walk(nodes, 0)
    }
}
