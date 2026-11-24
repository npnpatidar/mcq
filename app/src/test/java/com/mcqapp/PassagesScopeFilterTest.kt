package com.mcqapp

import com.mcqapp.domain.CategoryNode
import com.mcqapp.domain.Paper
import com.mcqapp.domain.Passage
import com.mcqapp.ui.editor.PassagesUiState
import com.mcqapp.ui.editor.buildPassagesPaperIndex
import com.mcqapp.ui.editor.choosePassagesPaper
import com.mcqapp.ui.editor.clearCategoriesInPassagesPaper
import com.mcqapp.ui.editor.clearPassagesScope
import com.mcqapp.ui.editor.prunePassagesScope
import com.mcqapp.ui.editor.toggleCategoryInChosenPassagesPaper
import com.mcqapp.ui.editor.toggleCategoryInPassagesPaper
import com.mcqapp.ui.editor.visiblePassages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passages get the same paper → categories scope as Search, but the filter is
 * applied to each passage's OWN `categoryId` (expanded to subcategories), never
 * to the categories of its member questions. Every helper here is pure, so the
 * contract runs on the JVM without an Application or a database.
 */
class PassagesScopeFilterTest {

    private fun cat(
        id: String,
        paper: String = "p1",
        children: List<CategoryNode> = emptyList()
    ) = CategoryNode(
        id = id, paperId = paper, title = id, parentId = null, children = children
    )

    // p1: Mechanics -> Kinematics (a nested category), plus Optics.
    private val physics = Paper(
        id = "p1",
        title = "Physics",
        categories = listOf(
            cat("p1-c1", children = listOf(cat("p1-c1a"))),
            cat("p1-c2")
        )
    )
    private val chemistry = Paper(
        id = "p2",
        title = "Chemistry",
        categories = listOf(cat("p2-c1", paper = "p2"))
    )
    private val papers = listOf(physics, chemistry)

    private fun passage(id: String, categoryId: String, members: Int = 0) = Passage(
        id = id, categoryId = categoryId, title = id, memberCount = members
    )

    /** Mechanics, Kinematics, Optics, Organic — in that order. */
    private val passages = listOf(
        passage("m", "p1-c1", members = 3),
        passage("k", "p1-c1a"),
        passage("o", "p1-c2"),
        passage("c", "p2-c1", members = 7)
    )

    // `this@PassagesScopeFilterTest` is essential: inside the extension,
    // `papers` would otherwise resolve to the state's (empty) papers field.
    private fun PassagesUiState.shown() = visiblePassages(
        this@PassagesScopeFilterTest.passages,
        this,
        buildPassagesPaperIndex(this@PassagesScopeFilterTest.papers)
    ).map { it.id }

    @Test
    fun noScopeShowsEveryPassageInEveryPaper() {
        assertEquals(listOf("m", "k", "o", "c"), PassagesUiState().shown())
        // Even a stale category id cannot narrow anything without a chosen paper.
        assertEquals(
            listOf("m", "k", "o", "c"),
            PassagesUiState(categoryFilterIds = setOf("p1-c1")).shown()
        )
    }

    @Test
    fun paperIndexReachesNestedCategories() {
        val index = buildPassagesPaperIndex(papers)
        assertEquals("p1", index["p1-c1a"]?.id)
        assertEquals("p2", index["p2-c1"]?.id)
        assertEquals(4, index.size)
    }

    @Test
    fun choosingOnePaperShowsOnlyThatPapersPassages() {
        assertEquals(listOf("m", "k", "o"), choosePassagesPaper(PassagesUiState(), "p1").shown())
        assertEquals(listOf("c"), choosePassagesPaper(PassagesUiState(), "p2").shown())
    }

    @Test
    fun tickingAParentIncludesEveryPassageInItsSubtree() {
        val scoped = choosePassagesPaper(PassagesUiState(), "p1")
        val ticked = toggleCategoryInChosenPassagesPaper(scoped, "p1-c1", papers)

        assertEquals(setOf("p1-c1", "p1-c1a"), ticked.categoryFilterIds)
        assertEquals(listOf("m", "k"), ticked.shown())
    }

    @Test
    fun tickingALeafShowsOnlyThatExactCategory() {
        val scoped = choosePassagesPaper(PassagesUiState(), "p1")
        val ticked = toggleCategoryInChosenPassagesPaper(scoped, "p1-c1a", papers)

        assertEquals(setOf("p1-c1a"), ticked.categoryFilterIds)
        assertEquals(listOf("k"), ticked.shown())
    }

    @Test
    fun severalTickedCategoriesUnionWithoutLosingAnEarlierOne() {
        val scoped = choosePassagesPaper(PassagesUiState(), "p1")
        val first = toggleCategoryInPassagesPaper(scoped, "p1-c1", physics.categories)
        val second = toggleCategoryInPassagesPaper(first, "p1-c2", physics.categories)

        assertEquals(listOf("m", "k", "o"), second.shown())
    }

    @Test
    fun untickingOneParentRemovesOnlyItsSubtree() {
        val scoped = choosePassagesPaper(PassagesUiState(), "p1")
        val both = toggleCategoryInPassagesPaper(
            toggleCategoryInPassagesPaper(scoped, "p1-c1", physics.categories),
            "p1-c2",
            physics.categories
        )
        val unticked = toggleCategoryInPassagesPaper(both, "p1-c1", physics.categories)

        assertEquals(setOf("p1-c2"), unticked.categoryFilterIds)
        assertEquals(listOf("o"), unticked.shown())
    }

    @Test
    fun filterReadsThePassageCategoryNotItsMembers() {
        // The passage with members lives outside the scope; the empty one is
        // inside it. A member-derived filter would get this backwards.
        val scoped = choosePassagesPaper(PassagesUiState(), "p1")
        val ticked = toggleCategoryInChosenPassagesPaper(scoped, "p1-c1", papers)

        assertEquals(listOf("m", "k"), ticked.shown())
        assertTrue("member count never decides scope", "c" !in ticked.shown())
    }

    @Test
    fun unknownCategoryAndUnknownPaperChangeNothingButYieldNoMatches() {
        val scoped = choosePassagesPaper(PassagesUiState(), "p1")
        assertSame(scoped, toggleCategoryInChosenPassagesPaper(scoped, "missing", papers))
        assertEquals(listOf("m", "k", "o"), scoped.shown())

        val unknown = choosePassagesPaper(PassagesUiState(), "missing")
        assertTrue("a paper that no category maps to has no passages", unknown.shown().isEmpty())
    }

    @Test
    fun switchingPaperDropsTheOldPapersCategoriesButRechoosingKeepsThem() {
        val p1 = toggleCategoryInPassagesPaper(
            choosePassagesPaper(PassagesUiState(), "p1"), "p1-c1", physics.categories
        )
        val rechosen = choosePassagesPaper(p1, "p1")
        assertEquals(setOf("p1-c1", "p1-c1a"), rechosen.categoryFilterIds)
        assertEquals(listOf("m", "k"), rechosen.shown())

        val p2 = choosePassagesPaper(p1, "p2")
        assertEquals(setOf("p2"), p2.paperFilterIds)
        assertTrue(p2.categoryFilterIds.isEmpty())
        assertEquals(listOf("c"), p2.shown())
    }

    @Test
    fun clearingCategoriesKeepsThePaperAndClearingScopeShowsAllAgain() {
        val scoped = toggleCategoryInChosenPassagesPaper(
            choosePassagesPaper(PassagesUiState(), "p1"), "p1-c1", papers
        )
        val categoriesCleared = clearCategoriesInPassagesPaper(scoped)
        assertEquals(setOf("p1"), categoriesCleared.paperFilterIds)
        assertEquals(listOf("m", "k", "o"), categoriesCleared.shown())

        val all = clearPassagesScope(categoriesCleared)
        assertTrue(all.paperFilterIds.isEmpty())
        assertTrue(all.categoryFilterIds.isEmpty())
        assertEquals(listOf("m", "k", "o", "c"), all.shown())
    }

    @Test
    fun pruningDropsADeletedPaperTogetherWithItsCategories() {
        val scoped = toggleCategoryInChosenPassagesPaper(
            choosePassagesPaper(PassagesUiState(), "p1"), "p1-c1", papers
        )
        val pruned = prunePassagesScope(scoped, listOf(chemistry))

        assertTrue(pruned.paperFilterIds.isEmpty())
        assertTrue(pruned.categoryFilterIds.isEmpty())
        assertEquals(listOf("m", "k", "o", "c"), pruned.shown())
    }

    @Test
    fun pruningDropsOnlyTheCategoryIdsThatNoLongerExist() {
        val scoped = toggleCategoryInChosenPassagesPaper(
            choosePassagesPaper(PassagesUiState(), "p1"), "p1-c1", papers
        )
        // Kinematics was deleted; Mechanics survives, so keep that one tick.
        val shrunkPhysics = physics.copy(
            categories = listOf(cat("p1-c1"), cat("p1-c2"))
        )
        val pruned = prunePassagesScope(scoped, listOf(shrunkPhysics, chemistry))

        assertEquals(setOf("p1"), pruned.paperFilterIds)
        assertEquals(setOf("p1-c1"), pruned.categoryFilterIds)
        assertEquals(listOf("m", "k"), pruned.shown())
    }

    @Test
    fun emptyPapersIsTheInitialLoadAndPrunesNothing() {
        val scoped = choosePassagesPaper(PassagesUiState(), "p1")

        assertSame(scoped, prunePassagesScope(scoped, emptyList()))
        assertEquals(listOf("m", "k", "o"), scoped.shown())
    }

    @Test
    fun pruningWithoutAPaperDropsOrphanCategoryIds() {
        val orphaned = PassagesUiState(categoryFilterIds = setOf("p1-c1"))

        val pruned = prunePassagesScope(orphaned, papers)

        assertTrue(pruned.categoryFilterIds.isEmpty())
        assertEquals(listOf("m", "k", "o", "c"), pruned.shown())
    }
}
