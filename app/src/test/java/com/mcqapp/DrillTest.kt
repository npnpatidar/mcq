package com.mcqapp

import com.mcqapp.domain.Drill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrillTest {

    /** The drill cut itself is [com.mcqapp.domain.SessionOrder.sampled]; these
     *  pin the count gate the dialog runs before a drill may start. */
    @Test
    fun aCountWithinThePaperIsAccepted() {
        assertEquals(Drill.CountCheck.Ok, Drill.checkCount(4, 10))
        assertEquals(Drill.CountCheck.Ok, Drill.checkCount(10, 10))
    }

    @Test
    fun askingForMoreThanThePaperHoldsIsRefused() {
        // The UI must refuse rather than quietly hand back a smaller drill.
        assertEquals(
            Drill.CountCheck.TooManyForPaper(999, 10),
            Drill.checkCount(999, 10)
        )
    }

    @Test
    fun anEmptyPaperCannotBeDrilled() {
        assertEquals(Drill.CountCheck.NoQuestionsAvailable, Drill.checkCount(10, 0))
    }
}
