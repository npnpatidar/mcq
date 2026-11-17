package com.mcqapp

import com.mcqapp.ui.importscreen.SingleShotGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fast double-tap delivers both clicks before the state flow can flip
 * `importing`. The button's gate is therefore an atomic compare-and-set, not a
 * state check, and it stays fired until a path that allows a new run releases
 * it.
 */
class FollowupImportGuardTest {

    @Test
    fun onlyTheFirstConcurrentTapStartsAnImport() {
        val guard = SingleShotGuard()
        assertTrue(guard.tryFire())
        assertFalse(guard.tryFire())
        assertFalse(guard.tryFire())
    }

    @Test
    fun aFailureOrDismissedReportAllowsANewRun() {
        val guard = SingleShotGuard()
        assertTrue(guard.tryFire())
        guard.release()
        assertTrue(guard.tryFire())
    }
}
