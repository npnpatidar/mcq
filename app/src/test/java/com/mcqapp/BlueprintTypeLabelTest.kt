package com.mcqapp

import com.mcqapp.R
import com.mcqapp.domain.TestType
import com.mcqapp.ui.common.testTypeLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The blueprint type label (plan §5.3): both label helpers use
 * `else -> RANDOM`, so a missing BLUEPRINT branch compiles and a blueprint
 * is silently labelled "Random". [testTypeLabelText] is @Composable (it
 * calls `stringResource`), so it cannot run in a pure JVM test; both helpers
 * delegate to the same resource mapping asserted here, which is also what
 * the session banner reads through [testTypeLabelRes].
 */
class BlueprintTypeLabelTest {

    @Test
    fun blueprintHasItsOwnLabelInsteadOfFallingThroughToRandom() {
        assertEquals(R.string.test_setup_type_blueprint, testTypeLabelRes(TestType.BLUEPRINT))
        assertNotEquals(
            testTypeLabelRes(TestType.RANDOM),
            testTypeLabelRes(TestType.BLUEPRINT)
        )
    }

    @Test
    fun randomStillMapsToTheRandomLabel() {
        assertEquals(R.string.test_setup_type_random, testTypeLabelRes(TestType.RANDOM))
    }
}
