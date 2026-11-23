package com.mcqapp

import com.mcqapp.domain.Paper
import com.mcqapp.ui.library.initialQuickCapturePaperId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A shared text snippet can open quick capture before the papers flow emits.
 * Saving with a blank selection would create a new paper instead of using the
 * learner's existing bank, so a blank choice picks the first paper as soon as
 * one arrives.
 */
class QuickCaptureDefaultPaperTest {

    private fun paper(id: String) = Paper(id = id, title = id)

    @Test
    fun aBlankChoicePicksTheFirstPaperWhenPapersArrive() {
        assertEquals("", initialQuickCapturePaperId("", emptyList()))
        assertEquals("p1", initialQuickCapturePaperId("", listOf(paper("p1"), paper("p2"))))
    }

    @Test
    fun anExistingChoiceIsNeverOverwritten() {
        assertEquals("p2", initialQuickCapturePaperId("p2", listOf(paper("p1"), paper("p2"))))
    }

    @Test
    fun papersThatNeverArriveLeaveTheChoiceBlankForTheNewPaperFlow() {
        assertEquals("", initialQuickCapturePaperId("", emptyList()))
    }
}
