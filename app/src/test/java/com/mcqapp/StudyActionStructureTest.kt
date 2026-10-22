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
        val start = card.indexOf("onClick = onStart")
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
        assertTrue("the tree must render a tri-state box", drawer.contains("TriStateCheckbox("))
        // Each of the three states is mapped explicitly, so a partial branch
        // cannot silently render as fully on or fully off.
        listOf(
            "CategoryCheckState.CHECKED -> ToggleableState.On",
            "CategoryCheckState.UNCHECKED -> ToggleableState.Off",
            "CategoryCheckState.INDETERMINATE -> ToggleableState.Indeterminate"
        ).forEach { mapping ->
            assertTrue("missing mapping: $mapping", drawer.contains(mapping))
        }
        // Computing states once per tree is what keeps a 1000-node paper O(n).
        assertTrue(
            "states must be built once for the tree",
            drawer.contains("CategorySelection.states(paper.categories, on)")
        )
        assertFalse(
            "state() per node would be O(n^2)",
            drawer.contains("CategorySelection.state(")
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
    fun theDrawerKeepsASelectionPerPaper() {
        val drawer = source("src/main/java/com/mcqapp/ui/library/LibraryDrawer.kt")
        // Declared once at DrawerContent scope...
        val declaration = drawer.indexOf("var onByPaper")
        val itemsLoop = drawer.indexOf("items(papers")
        assertTrue("the per-paper map is missing", declaration >= 0)
        assertTrue("the map must be declared before the paper list", declaration < itemsLoop)
        // ...and read per paper, keyed by its id.
        assertTrue(drawer.contains("onByPaper[paperId].orEmpty()"))
        assertTrue(drawer.contains("onByPaper = onByPaper + (paper.id to next)"))
        // A drawer-wide set is exactly the bug this replaced.
        assertFalse(drawer.contains("val checkedCategories = remember"))
        assertFalse(drawer.contains("mutableStateOf(setOf<String>())"))
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
