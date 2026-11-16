package com.mcqapp

import com.mcqapp.data.repository.FsrsTuner
import com.mcqapp.domain.FsrsFitResult
import com.mcqapp.domain.FsrsScheduler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The optimizer's storage gate on its own, without a DataStore: a fit is
 * persisted only when it actually ran and is strictly better.
 *
 * The persistence side ("the stored weights and `fsrs_fit_version` are
 * untouched") lives in [FsrsTunerPolicyTest], which needs Robolectric and so
 * only runs on CI's x86_64 runners. This class pins the decision itself on a
 * plain JVM, which is the part that had the m2 defect: a thin or empty trainset
 * returns the **shipped defaults** as its candidate with `steps = 0`, and those
 * defaults can score a better loss on that same thin data than the learner's
 * own fitted set — so "loss improved" alone used to overwrite a real fit.
 */
class FsrsFitStorageGateTest {

    private fun result(
        lossBefore: Double,
        lossAfter: Double,
        items: Int = 420,
        steps: Int = 9
    ) = FsrsFitResult(
        parameters = FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
        predictions = 400,
        items = items,
        cards = 80,
        lossBefore = lossBefore,
        lossAfter = lossAfter,
        steps = steps
    )

    @Test
    fun aRealFitWithAStrictlyBetterLossIsStorable() {
        assertTrue(FsrsTuner.isStorableFit(result(0.52, 0.41)))
    }

    /** `steps = 0` is the thin-data path that returns the defaults unchanged. */
    @Test
    fun aThinFitThatNeverTrainedIsNotStorable() {
        assertFalse(FsrsTuner.isStorableFit(result(0.52, 0.41, steps = 0)))
    }

    /**
     * No trainable prefix at all: nothing was fitted, so nothing can improve.
     * This is the reachable shape of an empty history — `items = 0` and
     * `steps = 0` come out of the same early return.
     */
    @Test
    fun anEmptyFitIsNotStorable() {
        assertFalse(FsrsTuner.isStorableFit(result(0.52, 0.41, items = 0, steps = 0)))
    }

    /**
     * Unreachable by construction, pinned anyway: `FsrsOptimizer.fit` reports
     * `items = 0` only on the branch that also leaves `steps = 0`, so no
     * fixture can train on zero prefixes. This guards the belt-and-braces half
     * of the predicate in case the optimizer's own gating ever changes.
     */
    @Test
    fun anItemlessFitThatSomehowTrainedIsStillNotStorable() {
        assertFalse(FsrsTuner.isStorableFit(result(0.52, 0.41, items = 0)))
    }

    /** Equal loss is not an improvement; the learner keeps what they had. */
    @Test
    fun anEqualLossIsNotStorable() {
        assertFalse(FsrsTuner.isStorableFit(result(0.44, 0.44)))
    }

    /** A non-finite loss is never "better". */
    @Test
    fun aNonFiniteLossIsNotStorable() {
        assertFalse(FsrsTuner.isStorableFit(result(0.52, Double.NaN)))
        assertFalse(FsrsTuner.isStorableFit(result(Double.NaN, 0.41)))
        assertFalse(FsrsTuner.isStorableFit(result(Double.NaN, Double.NaN)))
    }
}
