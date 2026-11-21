package com.mcqapp

import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestSetup
import com.mcqapp.domain.TestType
import com.mcqapp.domain.signals.Composition
import com.mcqapp.domain.signals.SelectionPreview
import com.mcqapp.ui.library.PanelLengthCopy
import com.mcqapp.ui.library.customPoolIsFlashcardsOnly
import com.mcqapp.ui.library.panelLengthCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flashcards-only copy for **custom pools**. A Bookmarked or Tag pool is
 * read testable-only, so an empty pool looks the same whether nothing is
 * bookmarked/tagged or everything there is a flashcard. [SelectionPreview]
 * carries both numbers so the sheet can tell them apart: content but nothing
 * testable gets the flashcards-only line, an actually empty scope keeps the
 * generic message.
 */
class TestSetupFlashcardsOnlyScopeTest {

    private fun preview(pool: Int, rawPool: Int) = SelectionPreview(
        pool = pool,
        rawPool = rawPool,
        composition = Composition(),
        targets = emptyList()
    )

    private fun setup(
        type: TestType,
        available: Int,
        flashcardsOnly: Boolean,
        variant: String? = null,
        requestedCount: Int = TestPlan.DEFAULT_LENGTH
    ) = TestSetup(
        paperId = "p1",
        categoryIds = emptyList(),
        scopeKey = ScopeKey.wholePaper("p1"),
        type = type,
        requestedCount = requestedCount,
        available = available,
        variant = variant,
        scopeIsFlashcardsOnly = flashcardsOnly
    )

    @Test
    fun aBookmarkedPoolOfOnlyFlashcardsSaysStudyOnly() {
        assertTrue(customPoolIsFlashcardsOnly(TestType.BOOKMARKED, preview(pool = 0, rawPool = 3)))

        val setup = setup(TestType.BOOKMARKED, available = 0, flashcardsOnly = true)
        assertFalse(setup.startable())
        assertEquals(TestPlan.NO_MULTIPLE_CHOICE_MESSAGE, setup.message())
    }

    @Test
    fun aTagPoolOfOnlyFlashcardsSaysStudyOnly() {
        assertTrue(customPoolIsFlashcardsOnly(TestType.TAG, preview(pool = 0, rawPool = 4)))

        val setup = setup(TestType.TAG, available = 0, flashcardsOnly = true, variant = "biology")
        assertFalse(setup.startable())
        assertEquals(TestPlan.NO_MULTIPLE_CHOICE_MESSAGE, setup.message())
    }

    @Test
    fun aGenuinelyEmptyCustomPoolKeepsTheGenericMessage() {
        assertFalse(customPoolIsFlashcardsOnly(TestType.BOOKMARKED, preview(pool = 0, rawPool = 0)))
        assertFalse(customPoolIsFlashcardsOnly(TestType.TAG, preview(pool = 0, rawPool = 0)))
        // No preview at all is "not known yet", never a verdict.
        assertFalse(customPoolIsFlashcardsOnly(TestType.TAG, null))

        val setup = setup(TestType.BOOKMARKED, available = 0, flashcardsOnly = false)
        assertFalse(setup.startable())
        assertEquals(TestPlan.EMPTY_SCOPE_MESSAGE, setup.message())
    }

    @Test
    fun aMixedCustomPoolIsNotFlashcardsOnly() {
        assertFalse(customPoolIsFlashcardsOnly(TestType.BOOKMARKED, preview(pool = 2, rawPool = 5)))
        assertFalse(customPoolIsFlashcardsOnly(TestType.TAG, preview(pool = 1, rawPool = 3)))
        // The helper only speaks for pools read outside the scope's signals.
        assertFalse(customPoolIsFlashcardsOnly(TestType.RANDOM, preview(pool = 0, rawPool = 5)))
    }

    @Test
    fun anAllCardPanelShowsNoLengthLineAndNoAskedRun() {
        val panel = setup(TestType.FIXED_PANEL, available = 0, flashcardsOnly = true, variant = "7")
        // The sheet draws the panel length only when a run can start, so the
        // all-card panel shows the flashcards-only note alone — no "Runs all
        // 0" and no asked/run line.
        assertEquals(0, panel.resolvedCount())
        assertFalse(panel.resolvedCount() > 0)
        assertTrue(panel.flashcardsOnlyScope())
        assertFalse(panel.showsAskedRun())
        assertEquals(TestPlan.NO_MULTIPLE_CHOICE_MESSAGE, panel.message())
        assertEquals(
            PanelLengthCopy.NONE,
            panelLengthCopy(panelSize = 5, resolvedCount = 0, showsAskedRun = false)
        )
    }

    @Test
    fun aPanelShowsRunsAllOnlyWhenItRunsTheWholeTestableSet() {
        // Frozen 5, all testable: there is no asked/run line, so "Runs all 5"
        // is the whole truth.
        val whole = setup(TestType.FIXED_PANEL, available = 5, flashcardsOnly = false, variant = "7", requestedCount = 5)
        assertEquals(5, whole.resolvedCount())
        assertFalse(whole.showsAskedRun())
        assertEquals(
            PanelLengthCopy.RUNS_ALL,
            panelLengthCopy(panelSize = 5, resolvedCount = 5, showsAskedRun = whole.showsAskedRun())
        )

        // Frozen 5, only 2 testable: the chip still says 5, so the runs-all
        // sentence is suppressed and the asked/run line carries the truth.
        val shortened = setup(TestType.FIXED_PANEL, available = 2, flashcardsOnly = false, variant = "7", requestedCount = 5)
        assertEquals(2, shortened.resolvedCount())
        assertTrue(shortened.showsAskedRun())
        assertEquals(
            PanelLengthCopy.ASKED_RUN,
            panelLengthCopy(panelSize = 5, resolvedCount = 2, showsAskedRun = shortened.showsAskedRun())
        )
        // No panel chosen at all: no length sentence.
        assertEquals(
            PanelLengthCopy.NONE,
            panelLengthCopy(panelSize = null, resolvedCount = 5, showsAskedRun = false)
        )
    }
}
