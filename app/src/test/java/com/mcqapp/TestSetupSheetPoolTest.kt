package com.mcqapp

import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestSetup
import com.mcqapp.domain.TestType
import com.mcqapp.ui.library.testablePoolAfterRead
import com.mcqapp.ui.library.testablePoolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup sheet's testable-count seam. A failed read is **unknown**, never
 * the raw total: the raw figure includes flashcards, and falling back to it
 * would enable Start on a pool the sampler cannot draw from. Unknown means
 * count 0 and the sheet still waiting, so Start stays disabled.
 */
class TestSetupSheetPoolTest {

    @Test
    fun aSuccessfulReadProvidesTheCount() {
        assertEquals(0, testablePoolAfterRead(Result.success(0)))
        assertEquals(7, testablePoolAfterRead(Result.success(7)))
        assertEquals(7, testablePoolState(7).count)
        assertFalse(testablePoolState(7).loading)
    }

    @Test
    fun aFailedReadStaysUnknownInsteadOfFallingBackToTheRawPool() {
        val failed = testablePoolAfterRead(Result.failure(IllegalStateException("database busy")))
        assertNull("a failed read is unknown, not a count", failed)

        val state = testablePoolState(failed)
        assertEquals("an unknown pool is zero, never the raw total", 0, state.count)
        assertTrue("the sheet keeps waiting, so Start is disabled", state.loading)
    }

    @Test
    fun aFlashcardsOnlyScopeWithAMistakesPoolOfZeroCannotStart() {
        // The consequence the failure path protects: an unknown or zero pool
        // must not become startable. The scope has content (all flashcards), so
        // the sheet says why rather than calling it empty.
        val mistakes = TestSetup(
            paperId = "p1",
            categoryIds = emptyList(),
            scopeKey = ScopeKey.wholePaper("p1"),
            type = TestType.MISTAKES,
            requestedCount = TestPlan.DEFAULT_LENGTH,
            available = 0,
            scopeIsFlashcardsOnly = true
        )
        assertFalse(mistakes.startable())
        assertEquals(TestPlan.NO_MULTIPLE_CHOICE_MESSAGE, mistakes.message())
    }
}
