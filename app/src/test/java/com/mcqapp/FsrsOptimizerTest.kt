package com.mcqapp

import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.FsrsTuner
import com.mcqapp.domain.FsrsOptimizer
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.FsrsTrainingCard
import com.mcqapp.domain.FsrsTrainingReview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

/**
 * The FSRS-7 parameter optimizer against the official reference trainer's
 * pinned pieces (open-spaced-repetition/fsrs-rs `training_v7.rs`, BSD-3-Clause)
 * and the behavioural guarantees the settings button leans on: a fit lowers the
 * loss on the learner's own history, stays inside the official bounds, is
 * deterministic, and refuses to train on data too thin to mean anything.
 *
 * FSRS-7 deleted the FSRS-6 preparation pass this test used to pin — the
 * ternary-search initial-stability fit, `smoothAndFill` and the outlier filter
 * are all gone, because `w0..w3` are now trained like every other weight. What
 * remains reference-pinned here is the schedule, the L2 term, the recency
 * weight and the post-fit monotonicity repair; the end-to-end fit uses
 * synthetic histories, where only the loss decrease, the bounds and the
 * determinism can be asserted (the reference's analytic gradients and f32
 * arithmetic make bit-identical weights impossible by design).
 *
 * The gradient itself is pinned twice over, as the audit requires: an exact
 * golden vector for a fixed history, and a Richardson-extrapolated reference
 * that proves the finite-difference path carries a bounded error. The bound is
 * not tighter than ~1e-4 because the replayed state is rounded to eight
 * decimals (FSRS-014), which injects a 5e-9 quantization into every
 * evaluation; measured worst case at the production step is 3.1e-5.
 */
class FsrsOptimizerTest {

    private val dayMs = 86_400_000L

    /** Epoch-ms base for the fixed fixtures. */
    private val t0 = 1_600_000_000_000L

    // ---- reference pieces: cosine schedule, L2, recency ----

    /** `lrAt` is the reference's 0.0118 cosine annealing over the step count. */
    @Test
    fun cosineScheduleMatchesTheReferenceSeries() {
        val expected = listOf(
            0.0118,
            0.011511233446141406,
            0.01067320026681219,
            0.009367932988525592,
            0.00772320026681219,
            0.0059,
            0.004076799733187811,
            0.002432067011474409,
            0.0011267997331878108,
            0.00028876655385859417,
            0.0
        )
        expected.forEachIndexed { step, value ->
            assertEquals(value, FsrsOptimizer.lrAt(step, iterations = 10), 1e-12)
        }
    }

    /**
     * `training_v7.rs`'s L2 term: 0.3333 total weight, per-parameter σ, applied
     * scaled by the batch's share of the trainset. Only w4 moves, so every
     * other gradient component must be exactly zero.
     */
    @Test
    fun l2PenaltyMatchesTheReferenceValueAndGradient() {
        val initial = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toDoubleArray()
        val weights = initial.clone().also { it[4] += 0.57 }
        val (value, gradient) = FsrsOptimizer.l2Penalty(
            weights = weights,
            target = initial,
            batchSize = 512,
            totalSize = 1000
        )
        assertEquals(0.20269900098344226, value, 1e-12)
        assertEquals(0.7112245648541834, gradient[4], 1e-12)
        gradient.forEachIndexed { index, grad ->
            if (index != 4) assertEquals(0.0, grad, 1e-12)
        }
    }

    /** `0.0667 + 0.9333·(i/n)^11.25`, pinned at the ends and the middle. */
    @Test
    fun recencyWeightMatchesTheReferenceFormula() {
        assertEquals(0.0667, FsrsOptimizer.recencyWeight(0, 100), 0.0)
        assertEquals(0.9002222647735063, FsrsOptimizer.recencyWeight(99, 100), 1e-12)
        assertEquals(0.06708320733611146, FsrsOptimizer.recencyWeight(50, 100), 1e-12)
    }

    // ---- bounds and post-fit repair ----

    @Test
    fun clipEnforcesTheOfficialBounds() {
        val clipped = FsrsOptimizer.clip(List(34) { 1_000_000.0 })
        clipped.forEachIndexed { index, value ->
            assertEquals(FsrsOptimizer.UPPER_BOUNDS[index], value, 1e-12)
        }
        val floored = FsrsOptimizer.clip(List(34) { -5.0 })
        floored.forEachIndexed { index, value ->
            assertEquals(FsrsOptimizer.LOWER_BOUNDS[index], value, 1e-12)
        }
    }

    /**
     * `NaN` survives a bare `coerceIn` (neither comparison holds), so the
     * clipper has to replace it with a deterministic in-bounds value; the
     * infinities are already handled by the clamp itself.
     */
    @Test
    fun clipRepairsNonFiniteParameters() {
        val nan = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toMutableList().also { it[3] = Double.NaN }
        val nanClipped = FsrsOptimizer.clip(nan)
        assertEquals(FsrsScheduler.DEFAULT_FSRS_PARAMETERS[3], nanClipped[3], 0.0)

        val infinite = List(34) { if (it % 2 == 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY }
        val clipped = FsrsOptimizer.clip(infinite)
        clipped.forEachIndexed { index, value ->
            assertTrue("w$index not finite", value.isFinite())
            assertTrue(value >= FsrsOptimizer.LOWER_BOUNDS[index])
            assertTrue(value <= FsrsOptimizer.UPPER_BOUNDS[index])
        }
        assertTrue(clipped[1] >= clipped[0])
        assertTrue(clipped[2] >= clipped[1])
        assertTrue(clipped[3] >= clipped[2])
        assertTrue(clipped[26] >= maxOf(clipped[25], 0.5))
    }

    /**
     * The audit's FSRS-012 matrix: every one of the 34 positions, once with
     * `NaN` and once with each infinity. A box clamp alone passes the infinite
     * cases but leaves `NaN` in place (`NaN.coerceIn(a, b)` is `NaN`), so each
     * value must be replaced or bounded **and** the result must still satisfy
     * both cross-parameter constraints.
     */
    @Test
    fun clipRepairsEveryPositionForEveryNonFiniteValue() {
        val nonFinite = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (index in 0 until FsrsScheduler.PARAMETER_COUNT) {
            for (value in nonFinite) {
                val parameters = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toMutableList()
                parameters[index] = value
                val clipped = FsrsOptimizer.clip(parameters)
                val actual = clipped[index]
                assertTrue("w$index = $value stayed non-finite: $actual", actual.isFinite())
                assertTrue(
                    "w$index = $actual below ${FsrsOptimizer.LOWER_BOUNDS[index]}",
                    actual >= FsrsOptimizer.LOWER_BOUNDS[index]
                )
                assertTrue(
                    "w$index = $actual above ${FsrsOptimizer.UPPER_BOUNDS[index]}",
                    actual <= FsrsOptimizer.UPPER_BOUNDS[index]
                )
                assertTrue("w1 < w0 for w$index", clipped[1] >= clipped[0])
                assertTrue("w2 < w1 for w$index", clipped[2] >= clipped[1])
                assertTrue("w3 < w2 for w$index", clipped[3] >= clipped[2])
                assertTrue(
                    "w26 anchor broken for w$index",
                    clipped[26] >= maxOf(clipped[25], 0.5)
                )
            }
        }
    }

    /**
     * `build_windowed_batches`: shortest history first, card id breaking ties,
     * and the window closes *before* a card that would cross the boundary.
     */
    @Test
    fun batchGroupsFollowTheReferencePreFlushRule() {
        // Exactly at the boundary stays together.
        assertEquals(listOf(listOf(0, 1)), FsrsOptimizer.batchGroups(intArrayOf(256, 256)))
        // Crossing it flushes first, rather than after.
        assertEquals(listOf(listOf(0), listOf(1)), FsrsOptimizer.batchGroups(intArrayOf(300, 300)))
        assertEquals(listOf(listOf(0, 1), listOf(2)), FsrsOptimizer.batchGroups(intArrayOf(255, 255, 255)))
        // A single card longer than the window forms its own batch.
        assertEquals(listOf(listOf(0)), FsrsOptimizer.batchGroups(intArrayOf(513)))
        // Ordering is by history length, not index: card 1 (5) precedes card 0 (600).
        assertEquals(listOf(listOf(1), listOf(0)), FsrsOptimizer.batchGroups(intArrayOf(600, 5)))
        // Equal histories tie-break on card id (the index).
        assertEquals(listOf(listOf(0, 1)), FsrsOptimizer.batchGroups(intArrayOf(200, 200)))
    }

    /**
     * The 511/512/513 boundary the audit calls out: one item below the window,
     * exactly on it, and one past it. A card that does not fit the *current*
     * window starts the next one; it never lands in a window that has already
     * been closed.
     */
    @Test
    fun batchGroupsPinTheWindowBoundary() {
        assertEquals(listOf(listOf(0)), FsrsOptimizer.batchGroups(intArrayOf(511)))
        assertEquals(listOf(listOf(0)), FsrsOptimizer.batchGroups(intArrayOf(512)))
        assertEquals(listOf(listOf(0)), FsrsOptimizer.batchGroups(intArrayOf(513)))
        // 511 + 1 fits; 511 + 2 does not, so the short card leads (size order)
        // and the long one follows alone.
        assertEquals(listOf(listOf(1, 0)), FsrsOptimizer.batchGroups(intArrayOf(511, 1)))
        assertEquals(listOf(listOf(1), listOf(0)), FsrsOptimizer.batchGroups(intArrayOf(511, 2)))
        // Two 256-card histories fill half a window together; the 512-card one
        // goes alone.
        assertEquals(
            listOf(listOf(1, 2), listOf(0)),
            FsrsOptimizer.batchGroups(intArrayOf(512, 256, 256))
        )
    }

    // ---- the objective, its gradient, and the pinning the audit asked for --

    /**
     * Two fixed complete histories: three predicted reviews on the first card
     * and two on the second, every gap at least a day, every grade used. The
     * fixture is deliberately tiny so its loss and gradient can be pinned
     * outright instead of only bounded.
     */
    private val goldenCards = listOf(
        FsrsTrainingCard(
            reviews = listOf(
                FsrsTrainingReview(3, t0),
                FsrsTrainingReview(1, t0 + dayMs),
                FsrsTrainingReview(3, t0 + 4 * dayMs),
                FsrsTrainingReview(4, t0 + 11 * dayMs)
            )
        ),
        FsrsTrainingCard(
            reviews = listOf(
                FsrsTrainingReview(2, t0 + 2 * dayMs),
                FsrsTrainingReview(3, t0 + 5 * dayMs),
                FsrsTrainingReview(3, t0 + 13 * dayMs)
            )
        )
    )

    /**
     * Upstream trains on the **unnormalized** weighted BCE sum, and the L2 term
     * is scaled to match; the mean is a reporting figure only. Pinning both the
     * sum and the denominator is what keeps the objective from silently
     * reverting to a normalized loss (which would leave L2 disproportionately
     * strong, exactly the FSRS-002 defect).
     */
    @Test
    fun theObjectiveIsTheUnnormalizedUpstreamSum() {
        val (sum, denominator) = FsrsOptimizer.objectiveOn(
            FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
            goldenCards
        )
        assertEquals(0.25140045270920774, sum, 1e-12)
        assertEquals(0.41233116103785256, denominator, 1e-12)
        // The mean is genuinely a different number: normalization would have
        // changed the gradient's scale.
        assertEquals(0.6097051992782297, sum / denominator, 1e-12)
        assertTrue(abs(sum / denominator - sum) > 0.1)
    }

    /**
     * Golden gradient of that objective at the official defaults, one entry per
     * weight. Zeros are structural (no history in this fixture starts on Again
     * or Easy, and some weights only enter transitions it never reaches), so
     * the vector doubles as a check that the right weights are wired to the
     * right transitions.
     *
     * The tolerance is 1e-6, not bit equality: a central difference divides the
     * evaluation's last-place difference (which `Math.pow`/`Math.log` do not
     * guarantee to be identical across platforms) by `2h`, so a handful of ulps
     * becomes ~1e-11. Any real defect — a sign, a missing clamp, a weight
     * wired to the wrong transition — moves a component by orders of magnitude
     * more than that.
     */
    @Test
    fun theGradientMatchesThePinnedReferenceVector() = runBlocking {
        val expected = doubleArrayOf(
            0.0, -0.005337067120157954, 0.008114870969848103, 0.0,
            -0.0044423738228929885, 0.05155841863949817, 0.00960157256363508,
            -0.011225919519913351, 0.00583379800300765, -0.009426517943159456,
            -0.023661653778299988, -0.04303610819657074, -0.001044635046931965,
            0.0, 0.0, -0.001198192215980618, -5.270974395367985E-4,
            -0.0010118871309156496, -0.0013178678324506185, -0.006651365714632438,
            -4.1301518765706066E-4, 0.0, 0.0, 0.017868945996557195,
            0.13545715548885187, 0.053461097887489976, -0.07404245435021126,
            -0.1952989872361909, 0.03917082803090285, 0.025046596038857945,
            0.048709309108733605, -0.13720342557022605, 0.050315659405053295,
            -0.006687094975190266
        )
        val gradient = FsrsOptimizer.gradientOn(FsrsScheduler.DEFAULT_FSRS_PARAMETERS, goldenCards)
        assertEquals(expected.size, gradient.size)
        gradient.forEachIndexed { index, value ->
            assertEquals("w$index", expected[index], value, 1e-6)
        }
    }

    /**
     * The audit's alternative to an analytic gradient: prove the
     * finite-difference path is accurate to a pinned tolerance. The reference
     * here is a Richardson extrapolation of central differences at 1e-4 and
     * 5e-5 days, evaluated through the same objective, so it is independent of
     * the production step (1e-5, widened by `|w|`).
     *
     * The tolerance is 2e-4 on a scale of 1. It cannot be tighter: the replayed
     * state is rounded to eight decimals, which puts a 5e-9 quantization into
     * every loss evaluation, and a central difference divides that by 2h —
     * measured worst case over this fixture is 3.1e-5.
     */
    @Test
    fun theFiniteDifferenceGradientTracksTheReferenceWithinThePinnedTolerance() = runBlocking {
        val weights = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toDoubleArray()
        fun centralDifference(step: Double): DoubleArray {
            val out = DoubleArray(weights.size)
            for (index in weights.indices) {
                val up = weights.copyOf().also { it[index] += step }
                val down = weights.copyOf().also { it[index] -= step }
                out[index] = (
                    FsrsOptimizer.objectiveOn(up.toList(), goldenCards).first -
                        FsrsOptimizer.objectiveOn(down.toList(), goldenCards).first
                    ) / (2.0 * step)
            }
            return out
        }
        val coarse = centralDifference(1e-4)
        val fine = centralDifference(5e-5)
        val production = FsrsOptimizer.gradientOn(weights.toList(), goldenCards)
        for (index in weights.indices) {
            val reference = (4.0 * fine[index] - coarse[index]) / 3.0
            val deviation = abs(production[index] - reference) / maxOf(1.0, abs(reference))
            assertTrue(
                "w$index: FD ${production[index]} vs reference $reference (deviation $deviation)",
                deviation <= 2e-4
            )
        }
    }

    /**
     * The BCE floors that the audit asked to see pinned: a prediction that has
     * fallen to the floor costs `-ln(1e-5)`, and one clamped at the ceiling
     * costs `-ln(1 - 1e-5)`. Without the clamps a saturated prediction would
     * score `±inf` and one `NaN` would poison the whole fit.
     */
    @Test
    fun theLossSaturatesAtTheUpstreamProbabilityFloorAndCeiling() {
        // A confident miss at either end costs the same clamped amount, never
        // an infinity.
        assertEquals(-ln(1e-5), FsrsOptimizer.bceTerm(0.0, label = 1.0, weight = 1.0), 1e-9)
        assertEquals(-ln(1e-5), FsrsOptimizer.bceTerm(1.0, label = 0.0, weight = 1.0), 1e-9)
        // A confident *hit* at either end still pays the ceiling's small cost:
        // the clamp is two-sided, so log(0) is unreachable from either side.
        assertEquals(-ln(1.0 - 1e-5), FsrsOptimizer.bceTerm(1.0, label = 1.0, weight = 1.0), 1e-9)
        assertEquals(-ln(1.0 - 1e-5), FsrsOptimizer.bceTerm(0.0, label = 0.0, weight = 1.0), 1e-9)
        // The recency weight scales the term, and negative probabilities clamp
        // up to the floor exactly like zero.
        assertEquals(2 * -ln(0.5), FsrsOptimizer.bceTerm(0.5, label = 1.0, weight = 2.0), 1e-12)
        assertEquals(-ln(1e-5), FsrsOptimizer.bceTerm(-3.0, label = 1.0, weight = 1.0), 1e-9)
        // A non-finite prediction is not silently repaired: it propagates, and
        // the tuner's strict-improvement test then refuses to store anything.
        assertTrue(FsrsOptimizer.bceTerm(Double.NaN, label = 1.0, weight = 1.0).isNaN())

        // End to end through the real curve: two reviews at the same instant
        // mean a zero gap, whose recall is exactly 1 - 1e-5, not 1.
        val ceilingCard = listOf(
            FsrsTrainingCard(
                reviews = listOf(
                    FsrsTrainingReview(3, t0),
                    FsrsTrainingReview(3, t0)
                )
            )
        )
        val (ceilingSum, ceilingDenominator) = FsrsOptimizer.objectiveOn(
            FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
            ceilingCard
        )
        assertEquals(-ln(1.0 - 1e-5), ceilingSum / ceilingDenominator, 1e-9)

        // And the floor is unreachable from *time* alone: even an elapsed time
        // as large as a Long allows leaves a finite recall above 1e-5, because
        // both traces' decay exponents are bounded. The clamp is defence in
        // depth, which is why it is pinned on `bceTerm` rather than here.
        val scheduler = FsrsScheduler()
        val state = scheduler.advanceMemory(scheduler.initial("q"), 1, 0.0)
        val recall = scheduler.recallAt(state, Long.MAX_VALUE.toDouble() / dayMs)
        assertTrue("recall $recall", recall > 1e-5)
        assertTrue(recall < 1.0)
    }

    /** An empty trainset is a zero-weight batch, not a division by zero. */
    @Test
    fun anEmptyTrainsetHasNoLossTerms() = runBlocking {
        val (sum, denominator) = FsrsOptimizer.objectiveOn(
            FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
            emptyList()
        )
        assertEquals(0.0, sum, 0.0)
        assertEquals(0.0, denominator, 0.0)
        val (l2Value, l2Gradient) = FsrsOptimizer.l2Penalty(
            FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toDoubleArray(),
            FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toDoubleArray(),
            batchSize = 0,
            totalSize = 0
        )
        assertEquals(0.0, l2Value, 0.0)
        assertTrue(l2Gradient.all { it == 0.0 })
        try {
            FsrsOptimizer.fit(emptyList(), FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("no review histories"))
        }
    }

    /**
     * A 1,100-review history: every review is a prediction target but the
     * reference's 1,024-position sequence cap decides how many of them the loss
     * and the gradient see. Counting them separately is what keeps the
     * "thick enough to fit" gauge honest.
     */
    @Test
    fun aHistoryLongerThanTheSequenceCapIsCountedButCapped() = runBlocking {
        val reviews = (0 until 1_100).map { i ->
            FsrsTrainingReview(if (i == 0) 3 else 1 + (i % 4), t0 + i * dayMs)
        }
        val result = FsrsOptimizer.fit(
            listOf(FsrsTrainingCard(reviews)),
            FsrsScheduler.DEFAULT_FSRS_PARAMETERS
        )
        assertEquals(1_099, result.items)
        // The cap is on sequence positions, and position 0 is the first
        // exposure (no prior state, so no prediction): 1,024 positions minus
        // that one leaves 1,023 predictions.
        assertEquals(FsrsOptimizer.MAX_SEQ_LEN - 1, result.predictions)
        assertTrue(result.steps > 0)
        assertTrue(result.lossAfter.isFinite())
    }

    /**
     * The fit is measured against the caller's weights but trains from the
     * official defaults, so a non-default `currentParameters` must change
     * `lossBefore` without changing where training starts. Only measuring
     * default-weight vectors could hide a fit that ignored the caller's set.
     */
    @Test
    fun aFitFromCustomCurrentWeightsMeasuresThemButTrainsFromTheDefaults() = runBlocking {
        val custom = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.mapIndexed { index, value ->
            if (index == 10) value * 1.5 else value
        }
        val cards = syntheticCards(count = 30, seed = 5)
        val fromDefaults = FsrsOptimizer.fit(cards, FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
        val fromCustom = FsrsOptimizer.fit(cards, custom)
        assertTrue(fromDefaults.steps > 0)
        assertNotEquals(fromDefaults.lossBefore, fromCustom.lossBefore)
        // Training starts at the defaults either way, so the fitted vector is
        // the same modulo the different starting measurement.
        fromCustom.parameters.forEachIndexed { index, value ->
            assertEquals("w$index", fromDefaults.parameters[index], value, 0.0)
        }
    }

    /**
     * Golden end-to-end fit: 17 cards, five reviews each, every gap fixed, so
     * the post-step parameters are reproducible. Pinned at 1e-3 rather than bit
     * equality: nine Adam steps amplify the gradient's last-place differences
     * (and the reference itself trains in f32 with analytic gradients), so the
     * tail digits differ across hosts while every meaningful change — objective,
     * clipping, batch order, learning rate — moves a weight by far more.
     */
    @Test
    fun aFitOnAFixedHistoryMatchesThePinnedParameters() = runBlocking {
        val cards = (0 until 17).map { card ->
            val base = t0 + card * 3 * dayMs
            FsrsTrainingCard(
                reviews = listOf(
                    FsrsTrainingReview(3, base),
                    FsrsTrainingReview(1 + (card % 4), base + (1 + card % 5) * dayMs),
                    FsrsTrainingReview(3, base + (6 + card % 11) * dayMs),
                    FsrsTrainingReview(1 + (card % 3), base + (20 + card % 17) * dayMs),
                    FsrsTrainingReview(3, base + (48 + card % 7) * dayMs)
                )
            )
        }
        val result = FsrsOptimizer.fit(cards, FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
        assertEquals(68, result.items)
        assertEquals(68, result.predictions)
        assertEquals(17, result.cards)
        assertEquals(9, result.steps)
        assertEquals(0.4273146633374264, result.lossBefore, 1e-9)
        assertEquals(0.3691584549783799, result.lossAfter, 1e-6)
        val expected = doubleArrayOf(
            0.1104, 2.2395, 3.8641243985783253, 11.7841, 6.193302272726055,
            0.60654505944028, 3.638820108534248, 1.9823612662534307, 0.0,
            1.3847116869664973, 0.7278293813715773, 0.6407994119480311,
            0.8181983350682648, 0.6529995482946775, 1.0, 1.3213990051848803,
            0.6714935749426792, 3.866674556352967, 0.4424445113402627,
            0.09697444448272545, 1.8649325870148465, 0.6172678421664559,
            1.087630346575309, 0.1565082567180749, 0.01932415150789341,
            0.24241602061355064, 0.930655940225823, 0.13237017736040646,
            0.7178566699049999, 0.0, 0.5664101060490392, 0.424442743964457,
            0.4951003869774314, 0.303746786228764
        )
        result.parameters.forEachIndexed { index, value ->
            assertEquals("w$index", expected[index], value, 1e-3)
        }
    }

    @Test
    fun clipEnforcesTheCrossParameterConstraints() {
        // A box clamp cannot express either rule, so clip has to apply them
        // afterwards: a legal set must satisfy both.
        val inverted = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toMutableList().also {
            it[0] = 10.0
            it[1] = 1.0
            it[2] = 0.5
            it[3] = 20.0
            it[25] = 0.9
            it[26] = 0.5
        }
        val clipped = FsrsOptimizer.clip(inverted)
        assertTrue(clipped[1] >= clipped[0])
        assertTrue(clipped[2] >= clipped[1])
        assertTrue(clipped[3] >= clipped[2])
        assertTrue(clipped[26] >= maxOf(clipped[25], 0.5))
        // w25's own upper bound is 0.85, so the anchor lands there, not on the
        // 0.9 the input asked for.
        assertEquals(0.85, clipped[26], 1e-12)
    }

    /** `smooth_initial_stabilities_fsrs7`: clamp to 1e-4..100, then run a max. */
    @Test
    fun smoothInitialStabilitiesClampsAndKeepsTheRatingOrder() {
        val smoothed = FsrsOptimizer.smoothInitialStabilitiesFsrs7(
            doubleArrayOf(5.0, 1.0, 8.0, -2.0)
        )
        assertEquals(5.0, smoothed[0], 0.0)
        assertEquals(5.0, smoothed[1], 0.0)
        assertEquals(8.0, smoothed[2], 0.0)
        assertEquals(8.0, smoothed[3], 0.0)

        val clamped = FsrsOptimizer.smoothInitialStabilitiesFsrs7(
            doubleArrayOf(-1.0, 500.0, 500.0, 500.0)
        )
        assertEquals(FsrsScheduler.STABILITY_MIN, clamped[0], 0.0)
        assertEquals(FsrsScheduler.INIT_S_MAX, clamped[3], 0.0)
    }

    // ---- end-to-end fit on synthetic histories ----

    /**
     * Reviews generated by the default scheduler's own dynamics: the fit must
     * find weights that predict them at least as well as the weights it
     * started measuring from, and every weight must stay inside the official
     * bounds the trainer clips to after every step.
     */
    @Test
    fun fitReducesTheLossOnSyntheticHistoryAndStaysInBounds() = runBlocking {
        val cards = syntheticCards(count = 80)
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = FsrsOptimizer.fit(cards, FsrsScheduler.DEFAULT_FSRS_PARAMETERS) { step, total ->
            progress += step to total
        }

        assertEquals(34, result.parameters.size)
        assertTrue(
            "loss should fall: ${result.lossBefore} -> ${result.lossAfter}",
            result.lossAfter < result.lossBefore
        )
        assertTrue("training should have run steps, got ${result.steps}", result.steps > 0)
        // Synthetic sequences are far shorter than the 1024-position cap.
        assertEquals(result.items, result.predictions)
        result.parameters.forEachIndexed { index, value ->
            assertTrue(
                "w$index = $value out of bounds",
                value >= FsrsOptimizer.LOWER_BOUNDS[index] - 1e-9 &&
                    value <= FsrsOptimizer.UPPER_BOUNDS[index] + 1e-9
            )
        }
        // The progress channel opens with the schedule size (0/total), then
        // reports every step exactly once, in order.
        assertEquals((0..result.steps).toList(), progress.map { it.first })
        assertTrue(progress.all { it.second == result.steps })
    }

    /** Same data, same seed, same weights — the button cannot jitter. */
    @Test
    fun fitIsDeterministic() = runBlocking {
        val cards = syntheticCards(count = 70, seed = 11)
        val first = FsrsOptimizer.fit(cards, FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
        val second = FsrsOptimizer.fit(cards, FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
        assertEquals(first.parameters, second.parameters)
        assertEquals(first.lossAfter, second.lossAfter, 0.0)
        assertEquals(first.steps, second.steps)
    }

    /**
     * Cancelling mid-fit — what navigating away from Settings does — stops
     * it at the next step boundary instead of grinding to the end, and the
     * cancellation propagates rather than reading as a fit failure.
     */
    @Test
    fun cancellingTheFitStopsItBetweenSteps() = runBlocking {
        val cards = syntheticCards(count = 80)
        var progressed = 0
        val job = launch {
            FsrsOptimizer.fit(cards, FsrsScheduler.DEFAULT_FSRS_PARAMETERS) { step, _ ->
                progressed += 1
                // Cancel from inside the first real Adam step (step 0 is the
                // initial schedule-size report), as leaving the screen would.
                if (step >= 1) this@launch.cancel()
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        // The schedule report plus the first step's callback — then it stopped.
        assertEquals(2, progressed)
    }

    /**
     * Below 64 trainset items the reference returns the defaults untouched
     * rather than failing, and so do we — even for a history this thin.
     */
    @Test
    fun fitReturnsDefaultsWhenHistoryIsTooThinToMeanAnything() = runBlocking {
        // Two complete cards, one predicted review each: two prefixes, well
        // under the training floor.
        val thin = listOf(
            FsrsTrainingCard(
                reviews = listOf(
                    FsrsTrainingReview(3, 1_600_000_000_000L),
                    FsrsTrainingReview(3, 1_600_000_000_000L + 4 * dayMs)
                )
            ),
            FsrsTrainingCard(
                reviews = listOf(
                    FsrsTrainingReview(1, 1_600_000_000_000L),
                    FsrsTrainingReview(4, 1_600_000_000_000L + 9 * dayMs)
                )
            )
        )
        val result = FsrsOptimizer.fit(thin, FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
        assertEquals(0, result.steps)
        assertEquals(2, result.items)
        assertEquals(FsrsScheduler.DEFAULT_FSRS_PARAMETERS, result.parameters)
    }

    /**
     * 8..63 review prefixes: the reference counts them but does not take an
     * Adam step, so the defaults come back untouched.
     */
    @Test
    fun fitReturnsInitializedWeightsBelowTheTrainingFloor() = runBlocking {
        val base = 1_600_000_000_000L
        val cards = (0 until 30).map { card ->
            FsrsTrainingCard(
                reviews = listOf(
                    FsrsTrainingReview(3, base),
                    FsrsTrainingReview(3, base + 7 * dayMs),
                    FsrsTrainingReview(1 + (card % 4), base + (8 + card % 23) * dayMs)
                )
            )
        }
        val result = FsrsOptimizer.fit(cards, FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
        assertEquals(0, result.steps)
        assertEquals(34, result.parameters.size)
        assertEquals(60, result.items)
        assertEquals(30, result.cards)
        result.parameters.forEachIndexed { index, value ->
            assertTrue(value >= FsrsOptimizer.LOWER_BOUNDS[index] - 1e-9)
            assertTrue(value <= FsrsOptimizer.UPPER_BOUNDS[index] + 1e-9)
        }
    }

    /**
     * A purely imported library: every history starts from stored memory, so
     * there are no first exposures. FSRS-7 no longer treats that as a reason to
     * keep w0..w3 fixed; thin data is the only reason the defaults come back.
     */
    @Test
    fun seededOnlyLibraryKeepsTheDefaultsWhenTheDataIsThin() = runBlocking {
        val cards = (0 until 12).map { card ->
            FsrsTrainingCard(
                reviews = listOf(
                    FsrsTrainingReview(3, 1_600_000_000_000L + card * dayMs),
                    FsrsTrainingReview(3, 1_600_000_000_000L + (card + 5) * dayMs)
                ),
                seedStability = 2.0,
                seedDifficulty = 5.0,
                seedReviewedAt = 1_600_000_000_000L - dayMs
            )
        }
        val result = FsrsOptimizer.fit(cards, FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
        assertEquals(0, result.steps)
        assertEquals(FsrsScheduler.DEFAULT_FSRS_PARAMETERS, result.parameters)
    }

    // ---- log -> training-card mapping (FsrsTuner) ----

    @Test
    fun trainingCardsAreBuiltFromTheLogInChronologicalOrder() {
        val day = dayMs
        val logs = listOf(
            log("q1", rating = 3, reviewedAt = 2 * day, stateBefore = "NEW"),
            log("q1", rating = 1, reviewedAt = day, stateBefore = null),
            log("q1", rating = 4, reviewedAt = 3 * day, stateBefore = "REVIEW")
        )
        val cards = FsrsTuner.buildFsrsTrainingCards(logs)
        assertEquals(1, cards.size)
        // Complete history: first exposure kept, ordered by time not id.
        assertEquals(listOf(1, 3, 4), cards.single().reviews.map { it.rating })
        assertEquals(null, cards.single().seedStability)
    }

    @Test
    fun anImportedHistorySeedsFromTheStateItsFirstRowRecorded() {
        val day = dayMs
        val first = log(
            "q1", rating = 3, reviewedAt = day, stateBefore = "REVIEW",
            stabilityAfter = 4.2, difficultyAfter = 6.1
        )
        val second = log("q1", rating = 2, reviewedAt = 6 * day, stateBefore = "REVIEW")
        val cards = FsrsTuner.buildFsrsTrainingCards(listOf(first, second))
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(4.2, card.seedStability!!, 0.0)
        assertEquals(6.1, card.seedDifficulty!!, 0.0)
        assertEquals(day, card.seedReviewedAt)
        // The seed row itself is not a review — it cannot be predicted.
        assertEquals(listOf(2), card.reviews.map { it.rating })
    }

    /**
     * Card identity is `(paperId, questionId)`: the same question in two papers
     * is two independent histories, never one interleaved card.
     */
    @Test
    fun theSameQuestionInTwoPapersStaysTwoTrainingCards() {
        val day = dayMs
        val logs = listOf(
            log("q1", rating = 3, reviewedAt = day, stateBefore = "NEW", paperId = "p1"),
            log("q1", rating = 1, reviewedAt = 2 * day, stateBefore = "NEW", paperId = "p2"),
            log("q1", rating = 2, reviewedAt = 3 * day, stateBefore = "REVIEW", paperId = "p1"),
            log("q1", rating = 4, reviewedAt = 4 * day, stateBefore = "REVIEW", paperId = "p2")
        )
        val cards = FsrsTuner.buildFsrsTrainingCards(logs)
        assertEquals(2, cards.size)
        // Each card sees only its own paper's two rows, in time order.
        assertEquals(listOf(3, 2), cards[0].reviews.map { it.rating })
        assertEquals(listOf(1, 4), cards[1].reviews.map { it.rating })
        cards.forEach { card -> assertEquals(null, card.seedStability) }
    }

    @Test
    fun aLoneImportedRowHasNothingToTrainOn() {
        val only = log(
            "q1", rating = 3, reviewedAt = dayMs, stateBefore = "REVIEW",
            stabilityAfter = 3.0, difficultyAfter = 5.0
        )
        assertTrue(FsrsTuner.buildFsrsTrainingCards(listOf(only)).isEmpty())
    }

    // ---- helpers ----

    /**
     * Review histories in the shape the study loop produces: a first exposure,
     * then reviews 1..30 days apart with grades drawn from a fixed seed. Every
     * gap is at least a day, so a few cards also carry a second long-term gap —
     * the condition the reference's "is there long-term memory at all?" guard
     * looks for before it will train.
     */
    private fun syntheticCards(count: Int, seed: Long = 7): List<FsrsTrainingCard> {
        val rng = kotlin.random.Random(seed)
        val base = 1_600_000_000_000L
        return (0 until count).map { card ->
            var at = base + rng.nextLong(0, 30 * dayMs)
            val reviews = ArrayList<FsrsTrainingReview>()
            repeat(3 + rng.nextInt(3)) {
                val rating = if (reviews.isEmpty()) 3 else 1 + rng.nextInt(4)
                reviews += FsrsTrainingReview(rating, at)
                at += (1 + rng.nextInt(30)) * dayMs
            }
            FsrsTrainingCard(reviews)
        }
    }

    private fun log(
        questionId: String,
        rating: Int,
        reviewedAt: Long,
        stateBefore: String?,
        stabilityAfter: Double? = null,
        difficultyAfter: Double? = null,
        paperId: String = "p1"
    ) = ReviewLogEntity(
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        stateBefore = stateBefore,
        stabilityAfter = stabilityAfter,
        difficultyAfter = difficultyAfter
    )
}
