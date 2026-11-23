package com.mcqapp

import com.mcqapp.ui.library.firstRunAfterSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The welcome chooser's one-shot marker must survive only a successful sample
 * load; a failed parse has to stay retryable and must not queue first study.
 */
class FirstRunAfterSampleTest {

    @Test
    fun aFailedParseNeitherMarksTheChooserNorQueuesStudy() {
        val decision = firstRunAfterSample(papersCreated = 0, availablePaperId = null)

        assertFalse(decision.markChooserShown)
        assertNull(decision.studyRoutePaperId)
    }

    @Test
    fun aFailedParseWithAnUnrelatedPaperStillDoesNotAdvance() {
        val decision = firstRunAfterSample(papersCreated = 0, availablePaperId = "paper-existing")

        assertFalse(decision.markChooserShown)
        assertNull(decision.studyRoutePaperId)
    }

    @Test
    fun aParseThatCreatedAPaperMarksTheChooserAndOffersThatPaper() {
        val decision = firstRunAfterSample(papersCreated = 1, availablePaperId = "paper-sample")

        assertTrue(decision.markChooserShown)
        assertEquals("paper-sample", decision.studyRoutePaperId)
    }

    @Test
    fun aParseWithNoVisiblePaperMarksButOffersNoStudyRoute() {
        val decision = firstRunAfterSample(papersCreated = 2, availablePaperId = null)

        assertTrue(decision.markChooserShown)
        assertNull(decision.studyRoutePaperId)
    }
}
