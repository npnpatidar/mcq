package com.mcqapp

import com.mcqapp.data.local.ReviewLogEntity
import com.mcqapp.data.repository.FsrsTuner
import com.mcqapp.domain.FsrsFitInsufficientData
import com.mcqapp.domain.FsrsOptimizer
import com.mcqapp.domain.FsrsScheduler
import com.mcqapp.domain.FsrsTrainingCard
import com.mcqapp.domain.FsrsTrainingReview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The FSRS parameter optimizer against the official reference trainer's own
 * pinned vectors (open-spaced-repetition/fsrs-rs, BSD-3-Clause) and the
 * behavioural guarantees the settings button leans on: a fit lowers the loss
 * on the learner's own history, stays inside the official bounds, is
 * deterministic, and refuses to run on data too thin to mean anything.
 *
 * The pure pieces (initial-stability search, smoothing, cosine schedule, L2)
 * are pinned to the reference's test vectors exactly; the end-to-end fit uses
 * synthetic histories, where only the loss decrease, the bounds and the
 * determinism can be asserted (the reference's analytic gradients and f32
 * arithmetic make bit-identical weights impossible by design).
 */
class FsrsOptimizerTest {

    private val dayMs = 86_400_000L

    // ---- reference vectors: initial stabilities ----

    /**
     * fsrs-rs `test_search_parameters`: the rating-1 slice of the anki21
     * sample's initialization dataset (delta_t / recall / count), fitted by
     * ternary search to the initial stability the reference reports.
     */
    @Test
    fun initialStabilitySearchMatchesTheReferenceVector() {
        val samples = buildList {
            fun add(delta: Int, positives: Int, negatives: Int) {
                repeat(positives) { add(FsrsOptimizer.InitSample(1, delta, true)) }
                repeat(negatives) { add(FsrsOptimizer.InitSample(1, delta, false)) }
            }
            add(1, 377, 58) // 435 items, recall 0.86666667
            add(2, 88, 9) //   97 items, recall 0.90721649
            add(3, 46, 17) //  63 items, recall 0.73015873
            add(4, 29, 9) //   38 items, recall 0.76315789
            add(5, 19, 9) //   28 items, recall 0.67857143
        }
        val fitted = FsrsOptimizer.searchInitialStabilities(samples, averageRecall = 0.94302857)
        // The reference asserts 0.7355089 in f32; the f64 port lands within
        // the same 1e-4 envelope its assert_approx_eq uses.
        assertEquals(0.7355089, fitted.getValue(1), 1e-4)
    }

    /** fsrs-rs `test_smooth_and_fill`, both published vectors. */
    @Test
    fun smoothAndFillMatchesTheReferenceVectors() {
        val counts = mapOf(1 to 1, 2 to 1, 3 to 1, 4 to 1)

        val filledFromThree = FsrsOptimizer.smoothAndFill(
            mutableMapOf(1 to 0.4, 3 to 2.3, 4 to 10.9), counts
        )!!
        assertEquals(0.4, filledFromThree[0], 1e-9)
        assertEquals(1.1227008, filledFromThree[1], 1e-5)
        assertEquals(2.3, filledFromThree[2], 1e-9)
        assertEquals(10.9, filledFromThree[3], 1e-9)

        val filledFromOne = FsrsOptimizer.smoothAndFill(mutableMapOf(2 to 0.35), counts)!!
        assertEquals(0.05738148, filledFromOne[0], 1e-5)
        assertEquals(0.35, filledFromOne[1], 1e-9)
        assertEquals(0.6242943, filledFromOne[2], 1e-5)
        assertEquals(2.2453482, filledFromOne[3], 1e-5)
    }

    /** The {2,3}-only shape fills BOTH missing ratings, as the reference does. */
    @Test
    fun smoothAndFillRepairsAnInvertedPairWithoutLosingARating() {
        val filled = FsrsOptimizer.smoothAndFill(
            mutableMapOf(2 to 3.0, 3 to 2.0),
            mapOf(2 to 5, 3 to 5)
        )!!
        // Equal counts settle the inversion on the pair's shared value, and
        // the missing ratings are filled geometrically around it — so all
        // four land on that shared stability.
        assertEquals(4, filled.size)
        filled.forEach { assertEquals(2.0, it, 1e-9) }
    }

    // ---- reference vectors: cosine schedule and L2 ----

    /** fsrs-rs `test_lr_scheduler`: the T=5 series the FSRS-6 trainer uses. */
    @Test
    fun cosineScheduleMatchesTheReferenceSeries() {
        val expected = listOf(
            0.04, 0.03618033988749895, 0.026180339887498946,
            0.013819660112501051, 0.0038196601125010526, 0.0
        )
        expected.forEachIndexed { step, value ->
            assertEquals(value, FsrsOptimizer.lrAt(step, iterations = 5), 1e-9)
        }
    }

    /** fsrs-rs `test_fsrs6_l2_penalty_applies_to_changed_params`. */
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
        assertEquals(0.256, value, 1e-6)
        assertEquals(0.8982456, gradient[4], 1e-6)
        gradient.forEachIndexed { index, grad ->
            if (index != 4) assertEquals(0.0, grad, 1e-12)
        }
    }

    // ---- bounds ----

    @Test
    fun clipEnforcesTheOfficialBounds() {
        val clipped = FsrsOptimizer.clip(List(21) { 1_000_000.0 })
        clipped.forEachIndexed { index, value ->
            assertEquals(FsrsOptimizer.UPPER_BOUNDS[index], value, 1e-12)
        }
        val floored = FsrsOptimizer.clip(List(21) { -5.0 })
        floored.forEachIndexed { index, value ->
            assertEquals(FsrsOptimizer.LOWER_BOUNDS[index], value, 1e-12)
        }
    }

    // ---- outlier filter ----

    /**
     * The tail rule from fsrs-rs `compute_outlier_bucket_decisions`: a tiny
     * far-away group dies even deep into the tail; a large near group lives.
     */
    @Test
    fun outlierFilterDropsTheSmallFarTail() {
        val samples = buildList {
            repeat(3) { add(FsrsOptimizer.InitSample(1, 200, false)) }
            repeat(30) { add(FsrsOptimizer.InitSample(1, 2, true)) }
        }
        val removed = FsrsOptimizer.computeRemovedPairs(samples)
        assertEquals(setOf(200), removed[1])
    }

    /** Under the 5% (min 20) quota every group is dropped without a glance. */
    @Test
    fun outlierFilterClearsEverythingBelowTheQuota() {
        val samples = (1..10).map { FsrsOptimizer.InitSample(1, it, true) }
        val removed = FsrsOptimizer.computeRemovedPairs(samples)
        assertEquals((1..10).toSet(), removed[1])
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

        assertEquals(21, result.parameters.size)
        assertTrue(
            "loss should fall: ${result.lossBefore} -> ${result.lossAfter}",
            result.lossAfter < result.lossBefore
        )
        assertTrue("training should have run steps, got ${result.steps}", result.steps > 0)
        // Synthetic sequences are far shorter than the 256-position cap.
        assertEquals(result.items, result.predictions)
        result.parameters.forEachIndexed { index, value ->
            assertTrue(
                "w$index = $value out of bounds",
                value >= FsrsOptimizer.LOWER_BOUNDS[index] - 1e-9 &&
                    value <= FsrsOptimizer.UPPER_BOUNDS[index] + 1e-9
            )
        }
        // The progress channel reports every step exactly once, in order.
        assertEquals((1..result.steps).toList(), progress.map { it.first })
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

    /** The reference returns defaults below 8 trainset items; we say so. */
    @Test
    fun fitRefusesHistoryTooThinToMeanAnything() = runBlocking {
        // Two cards, one inter-day review each, alone in their buckets — the
        // outlier filter empties the trainset, and an empty one is thin.
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
            assertTrue(expected.message!!.contains("0"))
            assertTrue(expected.message!!.contains("8"))
        }
    }

    /**
     * 8..63 surviving items: closed-form initial stabilities only, no Adam
     * steps. Thirty cards sharing one large first-gap bucket survive the
     * outlier filter; two inter-day reviews each keep the trainset at 60.
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
        assertEquals(21, result.parameters.size)
        assertEquals(60, result.items)
        assertEquals(30, result.cards)
        result.parameters.forEachIndexed { index, value ->
            assertTrue(value >= FsrsOptimizer.LOWER_BOUNDS[index] - 1e-9)
            assertTrue(value <= FsrsOptimizer.UPPER_BOUNDS[index] + 1e-9)
        }
    }

    /**
     * A purely imported library: every history starts from stored memory, so
     * there are no first exposures and w0..w3 cannot be trained — the
     * defaults are the honest answer, and the reference itself fails here.
     */
    @Test
    fun seededOnlyLibraryKeepsTheDefaultInitialStabilities() = runBlocking {
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
        assertEquals(
            FsrsScheduler.DEFAULT_FSRS_PARAMETERS,
            result.parameters.map { it }
        )
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
     * Inter-day review histories in the shape the study loop produces: a
     * first exposure, then reviews 1..30 days apart with grades drawn from a
     * fixed seed. First gaps cycle through three values so every first-rating
     * bucket is large enough to survive the reference's outlier filter (tiny
     * far buckets are dropped by design), and every gap is at least a day, so
     * every prefix after the first is a prediction target.
     */
    private fun syntheticCards(count: Int, seed: Long = 7): List<FsrsTrainingCard> {
        val rng = kotlin.random.Random(seed)
        val base = 1_600_000_000_000L
        val firstGaps = intArrayOf(3, 7, 14)
        return (0 until count).map { card ->
            var at = base + rng.nextLong(0, 30 * dayMs)
            val reviews = ArrayList<FsrsTrainingReview>()
            var first = true
            repeat(3 + rng.nextInt(3)) {
                // The first review's grade anchors the card's outlier bucket;
                // later grades are free to vary.
                val rating = if (first) 3 else 1 + rng.nextInt(4)
                reviews += FsrsTrainingReview(rating, at)
                at += (if (first) firstGaps[card % firstGaps.size] else 1 + rng.nextInt(30)) * dayMs
                first = false
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
