package com.mcqapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Layout decisions that no behaviour test pins down: the study-first action
 * order, the shared body text for stems and options, and the sticky-header
 * wiring that keeps the session context out of the scrolling question body.
 */
class StudyActionStructureTest {

    private fun source(path: String): String = File(path).readText()

    @Test
    fun studyComesFirstAndCarriesTheArrow() {
        val card = source("src/main/java/com/mcqapp/ui/library/LibraryPaperCard.kt")
        // Study sits alone above the action FlowRow: its label changes with
        // the due/new counts, so sharing a row shuffled its neighbours.
        val study = card.indexOf("onClick = onStudy")
        val flowRow = card.indexOf("FlowRow(")
        // Test branches inside the FlowRow now: a ticked card scopes it, an
        // untouched one starts the whole paper (E1).
        val start = card.indexOf("if (ticked.isEmpty()) onStart()")
        assertTrue("study action missing", study >= 0)
        assertTrue("test action missing", start >= 0)
        assertTrue("Study must lead, above the other actions", study < flowRow && flowRow < start)
        assertTrue(
            "Study fills the row so its changing label cannot resize it",
            Regex("""Button\(\s*\n\s*onClick = onStudy""").find(card) != null &&
                card.substringBefore("FlowRow(").contains("fillMaxWidth()"),
        )
        assertTrue(
            "the play arrow belongs to Study",
            card.indexOf("Icons.Default.PlayArrow") < flowRow
        )
    }

    @Test
    fun theOldStartStringIsGoneAndTheActionIsNamedTest() {
        val strings = source("src/main/res/values/strings.xml")
        assertTrue(strings.contains("""<string name="test_action">Test</string>"""))
        assertFalse(strings.contains("""<string name="start">Start</string>"""))
    }

    @Test
    fun questionsAndOptionsShareBodyText() {
        val screen = source("src/main/java/com/mcqapp/ui/study/StudyScreen.kt")
        assertTrue(
            screen.contains(
                "ContentElements(question.elements, textStyle = MaterialTheme.typography.bodyLarge)"
            )
        )
        assertTrue(
            screen.contains(
                "ContentElements(option.elements, textStyle = MaterialTheme.typography.bodyLarge)"
            )
        )
    }

    // --- the drawer's scoped actions (§9.6): source shape only ---

    @Test
    fun theDrawerOffersStudyBesideTest() {
        val drawer = source("src/main/java/com/mcqapp/ui/library/LibraryDrawer.kt")
        // Both actions receive this paper and the ticked ids, from the same block.
        assertTrue(drawer.contains("onStartStudy(paper.id, ticked)"))
        assertTrue(drawer.contains("onStartTest(paper.id, ticked)"))
        // And the ticked list is derived, not accumulated by hand.
        assertTrue(drawer.contains("CategorySelection.tickedIds("))
    }

    @Test
    fun theCategoryTreeUsesATriStateCheckbox() {
        val drawer = source("src/main/java/com/mcqapp/ui/library/LibraryDrawer.kt")
        val card = source("src/main/java/com/mcqapp/ui/library/LibraryPaperCard.kt")
        // Both surfaces render the same tri-state box with every state mapped
        // explicitly, so a partial branch cannot silently read as on or off.
        listOf(drawer to "the drawer", card to "the card").forEach { (file, name) ->
            assertTrue("$name must render a tri-state box", file.contains("TriStateCheckbox("))
            listOf(
                "CategoryCheckState.CHECKED -> ToggleableState.On",
                "CategoryCheckState.UNCHECKED -> ToggleableState.Off",
                "CategoryCheckState.INDETERMINATE -> ToggleableState.Indeterminate"
            ).forEach { mapping ->
                assertTrue("$name missing mapping: $mapping", file.contains(mapping))
            }
        }
        // Each surface computes states once per (tree, selection): state()
        // walks the subtree, so a per-node call is O(n^2) on a 1000-node paper.
        assertTrue(
            "the drawer must build states once for the tree",
            drawer.contains("CategorySelection.states(paper.categories, on)")
        )
        assertTrue(
            "the card must build states once for the tree",
            card.contains("CategorySelection.states(paper.categories, selected)")
        )
        assertFalse(
            "state() per node would be O(n^2) in the drawer",
            drawer.contains("CategorySelection.state(")
        )
        assertFalse(
            "state() per node would be O(n^2) in the card",
            card.contains("CategorySelection.state(")
        )
    }

    @Test
    fun theDrawerHasNoHardCodedActionLabels() {
        val drawer = source("src/main/java/com/mcqapp/ui/library/LibraryDrawer.kt")
        assertTrue(drawer.contains("R.string.study_n_categories"))
        assertTrue(drawer.contains("R.string.test_n_categories"))
        assertFalse(
            "the old literal label must be gone",
            drawer.contains("Start test (")
        )
        assertFalse(
            "no hard-coded count label",
            drawer.contains("categories)\"")
        )
    }

    @Test
    fun theSelectionHasOneOwner() {
        val viewModel = source("src/main/java/com/mcqapp/ui/library/LibraryViewModel.kt")
        val drawer = source("src/main/java/com/mcqapp/ui/library/LibraryDrawer.kt")
        val screen = source("src/main/java/com/mcqapp/ui/library/LibraryScreen.kt")
        // The ViewModel owns the selection; the drawer and the card read it.
        // Two owners for one paper is the category_study.md §7.1.1 bug class:
        // a drawer-wide set let paper A's ids ride along with paper B's.
        assertTrue(
            "the selection must be declared in the ViewModel",
            viewModel.contains("_selection = MutableStateFlow<Map<String, Set<String>>>")
        )
        assertFalse(
            "the drawer must not own a per-paper selection any more",
            drawer.contains("var onByPaper")
        )
        assertFalse(
            "the drawer must not write the selection",
            drawer.contains("onByPaper =")
        )
        assertFalse(
            "the drawer must not declare its own selection state",
            drawer.contains("mutableStateOf<Map<String, Set<String>>")
        )
        assertTrue(
            "the screen must hand the ViewModel's selection to the drawer",
            screen.contains("selectedByPaper = selection")
        )
    }

    @Test
    fun theCardShowsTheSelectionTree() {
        val card = source("src/main/java/com/mcqapp/ui/library/LibraryPaperCard.kt")
        // The eye opens the read-only picker; the scoped button starts a
        // session over the ticked branches.
        assertTrue("the card's eye button is missing", card.contains("paper-show-"))
        assertTrue(
            "the card's scoped study button is missing",
            card.contains("paper-study-selection-")
        )
        assertTrue(
            "the ticked branches must come from the shared model",
            card.contains("CategorySelection.tickedIds(")
        )
        // Ordering (D5): below the whole-paper Study button, above the FlowRow,
        // full width so its count-bearing label cannot resize neighbours.
        val study = card.indexOf("onClick = onStudy")
        val scoped = card.indexOf("paper-study-selection-")
        val flowRow = card.indexOf("FlowRow(")
        assertTrue("the whole-paper Study button is missing", study >= 0)
        assertTrue("the scoped button is missing", scoped >= 0)
        assertTrue(
            "the scoped button must sit between Study and the FlowRow",
            study < scoped && scoped < flowRow
        )
        assertTrue(
            "the scoped button must fill the width",
            Regex("""fillMaxWidth\(\)\s*\.testTag\("paper-study-selection-""").find(card) != null
        )
    }

    @Test
    fun exportAndTestFollowTheSelection() {
        val card = source("src/main/java/com/mcqapp/ui/library/LibraryPaperCard.kt")
        val screen = source("src/main/java/com/mcqapp/ui/library/LibraryScreen.kt")
        // Both scope-aware actions receive the ticked ids from the one shared
        // model; with nothing ticked they keep acting on the whole paper.
        assertTrue("Export must receive the ticked branches", card.contains("onExport(ticked)"))
        assertTrue("Test must accept a scoped list", card.contains("onScopedTest("))
        assertTrue(
            "Test must fall back to the whole paper when nothing is ticked",
            card.contains("if (ticked.isEmpty()) onStart() else onScopedTest(ticked)")
        )
        // The per-row Share is gone: its job is now "tick it in Show, Export".
        assertFalse(
            "the Manage row must not keep its own Share action",
            card.contains("Icons.Default.Share")
        )
        assertTrue(
            "the export dialog must name the selection",
            screen.contains("\"Export selection (")
        )
    }

    @Test
    fun theDrillRouteCarriesTheTickedBranches() {
        val card = source("src/main/java/com/mcqapp/ui/library/LibraryPaperCard.kt")
        val screen = source("src/main/java/com/mcqapp/ui/library/LibraryScreen.kt")
        // The dialog's bound and the route move with the selection (§15.1).
        assertTrue(
            "the dialog must bound a scoped drill by the selection",
            card.contains("CategorySelection.questionCount(paper.categories, ticked)")
        )
        assertTrue(
            "the card must hand the ticked branches to onDrill",
            card.contains("onDrill(drillCount!!, drillMinutes!!, ticked)")
        )
        val drill = screen.substringAfter("onDrill = {").substringBefore("onStudy =")
        assertTrue("the drill route must carry the ticked ids", drill.contains("categoryIds = ticked"))
        assertTrue("the drill route must keep its count", drill.contains("drillCount = count"))
    }

    @Test
    fun theCardBrowseAndTheRouteCarryTheTickedBranches() {
        val card = source("src/main/java/com/mcqapp/ui/library/LibraryPaperCard.kt")
        val screen = source("src/main/java/com/mcqapp/ui/library/LibraryScreen.kt")
        assertTrue(
            "the Browse button must pass the ticked branches",
            card.contains("onClick = { onBrowse(ticked) }")
        )
        val browse = screen.substringAfter("onBrowse = {").substringBefore("onExport")
        assertTrue(
            "the Browse route must carry the ticked ids as its category filter",
            browse.contains("categoryIds = ids")
        )
    }

    @Test
    fun theDrawerLabelsTheTickedBranchCount() {
        val drawer = source("src/main/java/com/mcqapp/ui/library/LibraryDrawer.kt")
        // ticked.size, not the expanded scope's size: one parent with twenty
        // descendants reads "1 category".
        assertTrue(drawer.contains("R.string.study_n_categories,\n"))
        assertTrue(drawer.contains("ticked.size"))
        assertFalse(
            "the label must not count expanded ids",
            drawer.contains("orderedIds(")
        )
    }
}
