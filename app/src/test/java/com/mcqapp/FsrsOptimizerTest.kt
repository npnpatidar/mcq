package com.mcqapp

import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.FsrsTuner
import com.mcqapp.domain.FsrsFitInsufficientData
import com.mcqapp.domain.FsrsOptimizer
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.FsrsTrainingCard
import com.mcqapp.domain.FsrsTrainingReview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

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
 */
class FsrsOptimizerTest {

    private val dayMs = 86_400_000L

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

    /** The reference returns defaults below 8 trainset items; we say so. */
    @Test
    fun fitRefusesHistoryTooThinToMeanAnything() = runBlocking {
        // Two complete cards, one predicted review each: two prefixes, well
        // under the eight the reference needs before a fit means anything.
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
        try {
            FsrsOptimizer.fit(thin, FsrsScheduler.DEFAULT_FSRS_PARAMETERS)
            fail("expected FsrsFitInsufficientData")
        } catch (expected: FsrsFitInsufficientData) {
            assertTrue(expected.message!!.contains("2"))
            assertTrue(expected.message!!.contains("8"))
        }
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
        difficultyAfter: Double? = null
    ) = ReviewLogEntity(
        paperId = "p1",
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        stateBefore = stateBefore,
        stabilityAfter = stabilityAfter,
        difficultyAfter = difficultyAfter
    )
}
