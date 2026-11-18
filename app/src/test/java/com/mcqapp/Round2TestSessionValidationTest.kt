package com.mcqapp

import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.ui.test.plannedSessionValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T7: `validationError` must gate every planned session in load(), including
 * the focus-selector branch that never reaches `finalPlan.resolve()`. The gate
 * is a pure seam over [TestPlan.validationError], so both the accepted and the
 * crafted/refused routes are JVM-testable.
 */
class Round2TestSessionValidationTest {

    private fun plan(
        type: TestType,
        exclusion: ExclusionMode = ExclusionMode.NONE,
        variant: String? = null
    ) = TestPlan(
        paperId = "p1",
        type = type,
        count = 20,
        scopeKey = ScopeKey.wholePaper("p1"),
        exclusionMode = exclusion,
        variant = variant
    )

    @Test
    fun legacyAndValidPlansPassTheGate() {
        assertNull("a legacy deep link has no plan to validate", plannedSessionValidationError(null, 100))
        assertNull(plannedSessionValidationError(plan(TestType.RANDOM), 100))
        // RECENT_3 is legal for the types that support a fresh sample.
        assertNull(
            plannedSessionValidationError(
                plan(TestType.RANDOM, ExclusionMode.RECENT_3), 100
            )
        )
    }

    @Test
    fun signalSelectorPlanWithIllegalFreshSampleIsRefused() {
        // TAG takes the focus-selector path but cannot claim a fresh sample.
        // Before the fix that branch never called validationError, so the
        // crafted route started and recorded a "Fresh sample" it never ran.
        assertNotNull("TAG must use the signal-selector path", TestType.TAG.usesSignalSelector)
        val error = plannedSessionValidationError(
            plan(TestType.TAG, ExclusionMode.RECENT_3, "tag-a"), 100
        )
        assertNotNull("a TAG+RECENT_3 plan must be refused", error)
        assertEquals(TestPlan.FRESH_MESSAGE, error)
    }

    @Test
    fun anEmptyScopeIsRefusedForEveryPlannedSession() {
        assertEquals(
            TestPlan.EMPTY_SCOPE_MESSAGE,
            plannedSessionValidationError(plan(TestType.RANDOM), 0)
        )
    }
}
