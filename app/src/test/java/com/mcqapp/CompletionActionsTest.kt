package com.mcqapp

import com.mcqapp.ui.common.CompletionAction
import com.mcqapp.ui.common.completionActionsFor
import com.mcqapp.ui.common.labelRes
import com.mcqapp.ui.common.testTag
import com.mcqapp.ui.settings.identifiableRestoredPaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM coverage for the completion-dialog action set shared by the
 * import and restore reports. Pins both the actions and their test tags: the
 * tags are the ones the Wave 1 Library dialog already uses, so a UI test that
 * looks for "import-study-now" keeps finding all three dialogs.
 */
class CompletionActionsTest {

    @Test
    fun aNamedPaperGetsStudyTestAndOpenPaperWithTheWaveOneTags() {
        assertEquals(
            listOf(CompletionAction.STUDY, CompletionAction.TEST, CompletionAction.OPEN_PAPER),
            completionActionsFor("p1")
        )
        assertEquals(
            listOf("import-study-now", "import-take-test", "import-open-paper"),
            listOf(
                CompletionAction.STUDY.testTag(),
                CompletionAction.TEST.testTag(),
                CompletionAction.OPEN_PAPER.testTag()
            )
        )
        assertEquals(
            listOf(
                com.mcqapp.R.string.of_study_now,
                com.mcqapp.R.string.of_take_test,
                com.mcqapp.R.string.of_open_paper
            ),
            listOf(
                CompletionAction.STUDY.labelRes(),
                CompletionAction.TEST.labelRes(),
                CompletionAction.OPEN_PAPER.labelRes()
            )
        )
    }

    @Test
    fun anUnidentifiablePaperOffersOnlyOpenLibrary() {
        assertEquals(listOf(CompletionAction.OPEN_LIBRARY), completionActionsFor(null))
        assertEquals("import-open-library", CompletionAction.OPEN_LIBRARY.testTag())
        assertEquals(com.mcqapp.R.string.wg_open_library, CompletionAction.OPEN_LIBRARY.labelRes())
    }

    @Test
    fun theRestorePickerUsesTheFirstSurvivingPaperOrNothing() {
        assertNull(identifiableRestoredPaper(emptyList()))
        assertEquals("p1", identifiableRestoredPaper(listOf("p1", "p2")))
    }
}
