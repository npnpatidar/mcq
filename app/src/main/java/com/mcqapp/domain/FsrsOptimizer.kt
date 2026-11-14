package com.mcqapp.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** One graded review of one card, as the optimizer reads the review log. */
data class FsrsTrainingReview(
    /** 1=Again, 2=Hard, 3=Good, 4=Easy. */
    val rating: Int,
    /** When it happened, epoch ms. Rows of a card must be chronological. */
    val reviewedAt: Long
)

/**
 * One card's review history in the shape the optimizer trains on.
 *
 * Two shapes, matching how a history can begin:
 *  - **Complete** ([seedStability] null): the first entry is the card's first
 *    exposure, replayed from a new card exactly as [FsrsScheduler.next] would.
 *  - **Seeded**: the history before the first logged review is unknown (an
 *    Anki import carrying memory), so [seedStability]/[seedStabilityFast]/
 *    [seedDifficulty] hold the state recorded after an earlier review and
 *    [reviews] starts at the next one. The seed row itself contributes no
 *    prediction, but its recorded state still feeds every later one.
 */
data class FsrsTrainingCard(
    val reviews: List<FsrsTrainingReview>,
    val seedStability: Double? = null,
    val seedStabilityFast: Double? = null,
    val seedDifficulty: Double? = null,
    val seedReviewedAt: Long = 0L
)

/**
 * The outcome of one [FsrsOptimizer.fit] run.
 *
 * @param parameters the fitted 34 weights, already clipped to the official
 *   FSRS-7 bounds (including the cross-parameter constraints) and ready to
 *   persist.
 * @param predictions how many review prefixes the loss was measured and trained
 *   on (every surviving prefix that fits the sequence cap).
 * @param items every surviving prefix before the sequence cap, the count the
 *   official training-size guards apply to.
 * @param lossBefore mean log loss of [currentParameters] on [predictions].
 * @param lossAfter mean log loss of [parameters] on the same predictions.
 * @param steps Adam steps executed; 0 when the data was too thin to train on.
 */
data class FsrsFitResult(
    val parameters: List<Double>,
    val predictions: Int,
    val items: Int,
    val cards: Int,
    val lossBefore: Double,
    val lossAfter: Double,
    val steps: Int
)

/** Not enough complete history to fit anything; the caller should say so. */
class FsrsFitInsufficientData(message: String) : IllegalStateException(message)

/**
 * Fits the 34 FSRS-7 weights to a learner's own review history — the app-side
 * counterpart of Anki's "Optimize" button.
 *
 * The training recipe follows the official reference trainer
 * (open-spaced-repetition/fsrs-rs `training.rs` / `training_v7.rs`,
 * BSD-3-Clause), whose tests pin the pieces this file ports:
 *
 *  - **Data**: unlike FSRS-6, FSRS-7 predicts on **every review after the
 *    first**, same-day reviews included, and `delta_t` stays fractional — the
 *    whole point of the dual-trace model is the sub-day interval. Labels are
 *    `rating > 1` and the sequence cap is 1024.
 *  - **No preparation pass.** FSRS-7 trains `w0..w3` like any other weight, so
 *    there is no closed-form initial-stability fit and no outlier filter: the
 *    fit starts from the official defaults and the history as recorded.
 *  - **Loss**: recency-weighted binary cross entropy of the predicted recall
 *    probability, p clamped to [1e-5, 1-1e-5], replayed through the app's own
 *    [FsrsScheduler] so training and scheduling can never disagree. Recency is
 *    the reference's `0.0667 + 0.9333·(i/n)^11.25`. Training and gradients use
 *    the **unnormalized** weighted sum, as upstream does, so the L2 term keeps
 *    its upstream scale; the mean is only reported to the UI
 *    ([FsrsFitResult.lossBefore]/[FsrsFitResult.lossAfter]).
 *  - **Optimizer**: Adam (β 0.70/0.98, ε 1e-8) on a cosine-annealed learning
 *    rate of 0.0118 over 9 epochs of card-windowed ~512-item batches, L2
 *    (0.3333, per-weight σ) toward the defaults, weights clipped to the
 *    official FSRS-7 bounds after every step.
 *  - **Post-fit**: the reference's `smooth_initial_stabilities_fsrs7` — clamp
 *    and enforce a non-decreasing `w0 ≤ w1 ≤ w2 ≤ w3`.
 *
 * Deliberate deviations, all documented at the code that makes them:
 *  - Gradients are central differences over the batch loss (68 evaluations a
 *    step) instead of the reference's forward-mode automatic differentiation;
 *    all arithmetic is f64 where the reference trains in f32, and the batch
 *    shuffle uses Kotlin's PRNG rather than the reference's StdRng, so fitted
 *    weights are close to the reference's but not bit-identical.
 *  - Imported cards replay from their stored post-review memory (a seed)
 *    instead of an unknown earlier history; the reference has no seeds.
 *  - The reference's optional schedule penalty (`enable_sched_penalties`,
 *    which Anki enables) is not implemented: it needs a differentiable
 *    interval simulation this app does not have, and it is not part of the
 *    default FSRS-7 recipe.
 */
object FsrsOptimizer {

    /**
     * Reviews required before the settings button unlocks. The official
     * guidance is ~1000 reviews for weights that are reliable; below that the
     * defaults predict better than a fit on noise.
     */
    const val MIN_REVIEWS = 1000

    internal const val LEARNING_RATE = 0.0118
    internal const val EPOCHS = 9
    internal const val BATCH_SIZE = 512
    internal const val RANDOM_SEED = 2023L
    internal const val ADAM_BETA_1 = 0.70
    internal const val ADAM_BETA_2 = 0.98
    internal const val ADAM_EPSILON = 1e-8
    internal const val L2_WEIGHT = 0.3333
    internal const val MAX_SEQ_LEN = 1024
    internal const val MIN_ITEMS_FOR_DEFAULTS = 8
    internal const val MIN_ITEMS_FOR_TRAINING = 64

    /** `analytic_v7.rs`'s BCE floor; the ceiling mirrors it. */
    private const val PROBABILITY_FLOOR = 0.00001
    private const val PROBABILITY_CEILING = 0.99999
    private const val FD_STEP = 1e-5

    /** Per-weight σ the reference L2 penalty normalizes by (fsrs-rs training_v7). */
    private val PARAMS_STDDEV = doubleArrayOf(
        9999.0, 9999.0, 9999.0, 9999.0, 0.523, 0.2528, 0.4329, 0.2966, 0.2139,
        0.2889, 0.1862, 0.175, 0.3812, 0.3013, 0.9104, 0.3234, 0.2448, 0.3273,
        0.1842, 0.1735, 0.4608, 0.311, 0.864, 0.0418, 0.2596, 0.0798, 0.0682,
        0.1282, 0.1397, 0.1407, 0.1489, 0.2, 0.15, 0.15
    )

    /** Official FSRS-7 parameter bounds (fsrs-rs parameter_clipper_v7). */
    internal val LOWER_BOUNDS = doubleArrayOf(
        0.0001, 0.0001, 0.0001, 0.0001,
        1.0, 0.001, 0.1,
        0.0, 0.0, 0.3, 0.01, 0.1, 0.0, 0.0, 1.0,
        0.0, 0.0, 0.5, 0.001, 0.001, 0.0, 0.0, 1.0,
        0.01, 0.01, 0.2, 0.5, 0.01, 0.1, 0.0, 0.1, 0.0, 0.0, 0.0
    )
    internal val UPPER_BOUNDS = doubleArrayOf(
        50.0, 100.0, 100.0, 100.0,
        10.0, 4.0, 4.0,
        4.0, 1.2, 3.0, 1.5, 1.0, 3.5, 1.0, 7.0,
        4.0, 2.0, 6.0, 1.5, 1.0, 5.0, 1.0, 7.0,
        0.25, 0.95, 0.85, 0.99, 1.0, 1.0, 0.9, 1.1, 1.0, 0.6, 0.6
    )

    /**
     * Clamps [parameters] into the official bounds, preserving order.
     *
     * A box clamp alone cannot express FSRS-7's two cross-parameter rules — the
     * initial stabilities must not invert (`w0 ≤ w1 ≤ w2 ≤ w3`) and the slow
     * anchor must sit above the fast one (`w26 ≥ max(w25, 0.5)`) — so those are
     * applied after it. `FsrsScheduler` relies on both.
     */
    fun clip(parameters: List<Double>): List<Double> {
        require(parameters.size == FsrsScheduler.PARAMETER_COUNT) {
            "FSRS-7 needs ${FsrsScheduler.PARAMETER_COUNT} parameters, got ${parameters.size}"
        }
        val weights = parameters.toDoubleArray()
        clipInPlace(weights)
        return weights.toList()
    }

    /**
     * Fits [cards] to their own history and returns the result; nothing is
     * persisted here. [currentParameters] is only measured for
     * [FsrsFitResult.lossBefore] — training starts from the official defaults,
     * not from the weights the user happens to have.
     *
     * Runs on [Dispatchers.Default] internally; [onProgress] first reports
     * the schedule size as 0/total, then fires after each Adam step, and the
     * coroutine's cancellation stops between steps and inside the gradient
     * fan-out.
     *
     * @throws FsrsFitInsufficientData when fewer than
     *   [MIN_ITEMS_FOR_DEFAULTS] review prefixes exist.
     */
    suspend fun fit(
        cards: List<FsrsTrainingCard>,
        currentParameters: List<Double>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): FsrsFitResult {
        require(cards.isNotEmpty()) { "no review histories to fit" }
        require(currentParameters.size == FsrsScheduler.PARAMETER_COUNT) {
            "FSRS-7 needs ${FsrsScheduler.PARAMETER_COUNT} parameters, got ${currentParameters.size}"
        }
        // The whole recipe — every full-log loss replay and every Adam step —
        // runs on Dispatchers.Default so a heavy library's fit cannot jank the
        // settings UI. [onProgress] writes a StateFlow, which is safe from any
        // thread.
        return withContext(Dispatchers.Default) {
            doFit(cards, currentParameters, onProgress)
        }
    }

    private suspend fun doFit(
        cards: List<FsrsTrainingCard>,
        currentParameters: List<Double>,
        onProgress: (Int, Int) -> Unit
    ): FsrsFitResult {
        val prepared = cards.map { prepare(it) }

        // Every review after the first is a prediction target — same-day ones
        // included. A complete history's first row has no prior state to
        // predict from; a seeded one starts at its first logged review, whose
        // pre-state is the recorded seed.
        val trainItems = ArrayList<ItemRef>()
        prepared.forEachIndexed { cardIndex, card ->
            val first = if (card.seeded) 0 else 1
            for (pos in first until card.size) trainItems += ItemRef(cardIndex, pos)
        }
        if (trainItems.size < MIN_ITEMS_FOR_DEFAULTS) {
            throw FsrsFitInsufficientData(
                "Only ${trainItems.size} review prefixes found; " +
                    "at least $MIN_ITEMS_FOR_DEFAULTS needed"
            )
        }

        // Recency weights over trainset items ordered by the review they
        // predict, the reference's 0.0667 + 0.9333·(i/n)^11.25, assigned before
        // the sequence cap is applied.
        val weightByCard = Array(prepared.size) { card ->
            DoubleArray(prepared[card].size) { -1.0 }
        }
        val byTime = trainItems.sortedWith(
            compareBy({ prepared[it.card].times[it.pos] }, { it.card }, { it.pos })
        )
        val weightDenominator = max(byTime.size, 1)
        byTime.forEachIndexed { index, item ->
            weightByCard[item.card][item.pos] = recencyWeight(index, weightDenominator)
        }

        val initialParameters = FsrsScheduler.DEFAULT_FSRS_PARAMETERS.toDoubleArray()

        // Loss terms: every trainset item within the reference's sequence cap.
        val lossTerms = trainItems.filter { it.pos + 1 <= MAX_SEQ_LEN }
        val totalSize = lossTerms.size
        val groupedTerms = groupTerms(lossTerms, prepared, weightByCard)

        // Card-windowed batches: whole cards in time order, closed when the
        // window reaches BATCH_SIZE items, so one card's sequence never spans
        // two gradients. This replaces FSRS-6's sort-by-length chunking.
        val batches = windowedBatches(lossTerms, prepared, weightByCard)
        val totalSteps = batches.size * EPOCHS
        // FSRS-7 anneals over the actual batch count (FSRS-6 kept the
        // floor+1 estimate, which is why this number differs from the old one).
        val iterations = totalSteps

        // Publish the schedule size before the first (whole-log) replay so the
        // button shows 0/total immediately instead of 0/0 while the baseline
        // loss is measured.
        onProgress(0, totalSteps)

        val lossBefore = evaluateBatchLoss(
            currentParameters.toDoubleArray(),
            prepared,
            groupedTerms
        ).mean

        // The reference returns the initialized (default) weights untouched
        // when the trainset is thin, or when nothing in it has more than one
        // long-term review — there is no long-term memory to describe.
        val hasLongTermDepth = prepared.any { card -> card.deltas.count { it >= 1.0 } >= 2 }
        val enoughToTrain = trainItems.size >= MIN_ITEMS_FOR_TRAINING &&
            hasLongTermDepth &&
            groupedTerms.isNotEmpty()
        if (!enoughToTrain) {
            val lossAfter = evaluateBatchLoss(initialParameters, prepared, groupedTerms).mean
            return FsrsFitResult(
                parameters = initialParameters.toList(),
                predictions = lossTerms.size,
                items = trainItems.size,
                cards = prepared.size,
                lossBefore = lossBefore,
                lossAfter = lossAfter,
                steps = 0
            )
        }

        val weights = initialParameters.copyOf()
        val target = initialParameters.copyOf()
        val adam = Adam(weights.size)
        val rng = kotlin.random.Random(RANDOM_SEED)
        val order = batches.indices.toMutableList()
        var step = 0
        for (epoch in 0 until EPOCHS) {
            order.shuffle(rng)
            for (batchIndex in order) {
                val batch = batches[batchIndex]
                val gradient = gradient(weights, batch, prepared, target, totalSize)
                adam.step(weights, gradient, lrAt(step, iterations))
                clipInPlace(weights)
                step += 1
                onProgress(step, totalSteps)
                coroutineContext.ensureActive()
            }
        }

        val smoothed = smoothInitialStabilitiesFsrs7(weights.copyOfRange(0, 4))
        val clipped = weights.copyOf()
        for (i in 0 until 4) clipped[i] = smoothed[i]
        clipInPlace(clipped)
        val lossAfter = evaluateBatchLoss(clipped, prepared, groupedTerms).mean
        return FsrsFitResult(
            parameters = clipped.toList(),
            predictions = lossTerms.size,
            items = trainItems.size,
            cards = prepared.size,
            lossBefore = lossBefore,
            lossAfter = lossAfter,
            steps = totalSteps
        )
    }

    // --- data preparation -------------------------------------------------

    private class ItemRef(val card: Int, val pos: Int)

    private class CardTerms(
        val card: Int,
        val positions: IntArray,
        val labels: DoubleArray,
        val weights: DoubleArray
    )

    private class PreparedCard(
        val ratings: IntArray,
        /** Elapsed fractional days before each review; position 0 is the seed gap or 0. */
        val deltas: DoubleArray,
        val times: LongArray,
        val seedStability: Double?,
        val seedStabilityFast: Double?,
        val seedDifficulty: Double?
    ) {
        val size: Int = ratings.size
        val seeded: Boolean = seedStability != null

        fun initialState(): CardState =
            if (seeded) {
                val stability = seedStability!!.coerceAtLeast(FsrsScheduler.STABILITY_MIN)
                CardState(
                    questionId = "fsrs-fit",
                    stability = stability,
                    // A seed written before v27 has no fast trace; collapsing it
                    // onto the slow one is the same rule the scheduler applies.
                    stabilityFast = (seedStabilityFast ?: stability)
                        .coerceAtLeast(FsrsScheduler.STABILITY_MIN),
                    difficulty = seedDifficulty!!.coerceIn(
                        FsrsScheduler.MIN_DIFFICULTY,
                        FsrsScheduler.MAX_DIFFICULTY
                    ),
                    lastReviewedAt = 0L,
                    state = CardStateKind.REVIEW
                )
            } else {
                CardState(questionId = "fsrs-fit")
            }
    }

    private fun prepare(card: FsrsTrainingCard): PreparedCard {
        require(card.reviews.isNotEmpty()) { "a training card needs at least one review" }
        val size = card.reviews.size
        val seeded = card.seedStability != null
        if (seeded) {
            require(card.seedDifficulty != null) { "seeded cards need a seed difficulty" }
        }
        val ratings = IntArray(size) {
            val rating = card.reviews[it].rating
            require(rating in 1..4) { "rating must be 1..4, got $rating" }
            rating
        }
        val deltas = DoubleArray(size)
        if (seeded) {
            deltas[0] = daysBetween(card.seedReviewedAt, card.reviews[0].reviewedAt)
        }
        for (i in 1 until size) {
            deltas[i] = daysBetween(card.reviews[i - 1].reviewedAt, card.reviews[i].reviewedAt)
        }
        val times = LongArray(size) { card.reviews[it].reviewedAt }
        return PreparedCard(
            ratings = ratings,
            deltas = deltas,
            times = times,
            seedStability = card.seedStability,
            seedStabilityFast = card.seedStabilityFast,
            seedDifficulty = card.seedDifficulty
        )
    }

    /**
     * Fractional days between two instants, clamped at 0. FSRS-7 trains on the
     * real gap: rounding to whole days would erase the sub-day reviews the fast
     * trace exists to describe.
     */
    private fun daysBetween(from: Long, to: Long): Double =
        (to - from).coerceAtLeast(0L) / DayBoundary.DAY_MS.toDouble()

    private fun groupTerms(
        refs: List<ItemRef>,
        prepared: List<PreparedCard>,
        weightByCard: Array<DoubleArray>
    ): List<CardTerms> = refs.groupBy { it.card }.map { (cardIndex, cardRefs) ->
        CardTerms(
            card = cardIndex,
            positions = IntArray(cardRefs.size) { cardRefs[it].pos },
            labels = DoubleArray(cardRefs.size) {
                if (prepared[cardIndex].ratings[cardRefs[it].pos] > 1) 1.0 else 0.0
            },
            weights = DoubleArray(cardRefs.size) { weightByCard[cardIndex][cardRefs[it].pos] }
        )
    }

    /**
     * [batchGroups]' layout over the cards that actually have items, in card
     * order. Whole cards, so a card's sequence never spans two gradients.
     */
    private fun windowedBatches(
        items: List<ItemRef>,
        prepared: List<PreparedCard>,
        weightByCard: Array<DoubleArray>
    ): List<List<CardTerms>> {
        if (items.isEmpty()) return emptyList()
        val refsByCard = items.groupBy { it.card }
        val cardOrder = refsByCard.keys.sorted()
        val sizes = IntArray(cardOrder.size) { refsByCard.getValue(cardOrder[it]).size }
        return batchGroups(sizes).map { group ->
            groupTerms(
                group.flatMap { refsByCard.getValue(cardOrder[it]) },
                prepared,
                weightByCard
            )
        }
    }

    /**
     * The reference's batch layout over card sizes (`training.rs`
     * `build_windowed_batches`): cards ordered by `(full history length, card
     * id)` — [cardSizes] is already in card-id order, so the index breaks
     * ties — and a window is closed **before** a card that would take it past
     * [batchSize].
     *
     * The old time-ordered, post-flush window let a long card push a batch far
     * past 512 items, changing batch membership, the effective learning rate
     * and memory use. A single card larger than the window still forms its own
     * (oversized) batch, as upstream does.
     *
     * @return batches as indices into [cardSizes].
     */
    internal fun batchGroups(
        cardSizes: IntArray,
        batchSize: Int = BATCH_SIZE
    ): List<List<Int>> {
        val order = cardSizes.indices.sortedWith(compareBy({ cardSizes[it] }, { it }))
        val batches = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var count = 0
        for (card in order) {
            if (current.isNotEmpty() && count + cardSizes[card] > batchSize) {
                batches += current
                current = ArrayList()
                count = 0
            }
            current += card
            count += cardSizes[card]
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    // --- initial stabilities (fsrs-rs parameter_initialization_fsrs7) ------

    /**
     * Repairs fitted initial stabilities: clamps into the official range and
     * forces the rating order monotonic (`w0 ≤ w1 ≤ w2 ≤ w3`).
     *
     * The reference's log-space anchor interpolation (`-8.09, -3.83, -2.5,
     * -1.0`) only fires when a rating has no value at all, which cannot happen
     * here: FSRS-7 trains all four weights, so only the clamp and the running
     * max are reachable.
     */
    internal fun smoothInitialStabilitiesFsrs7(initial: DoubleArray): DoubleArray {
        require(initial.size == 4) { "expected 4 initial stabilities, got ${initial.size}" }
        val out = DoubleArray(4)
        var previous = FsrsScheduler.STABILITY_MIN
        for (i in 0 until 4) {
            val value = initial[i].coerceIn(FsrsScheduler.STABILITY_MIN, FsrsScheduler.INIT_S_MAX)
            out[i] = max(value, previous)
            previous = out[i]
        }
        return out
    }

    // --- training ----------------------------------------------------------

    /** The reference's recency weight for a 0-based item index. */
    internal fun recencyWeight(index: Int, count: Int): Double {
        val ratio = index.toDouble() / max(count, 1)
        return 0.0667 + 0.9333 * ratio.pow(11.25)
    }

    /** The reference's cosine schedule at 0-based step over [iterations]. */
    internal fun lrAt(step: Int, iterations: Int): Double =
        LEARNING_RATE * (1.0 + cos(Math.PI * step / iterations)) / 2.0

    /** The reference L2 penalty: toward [target], scaled by batch over total. */
    internal fun l2Penalty(
        weights: DoubleArray,
        target: DoubleArray,
        batchSize: Int,
        totalSize: Int
    ): Pair<Double, DoubleArray> {
        val gradient = DoubleArray(weights.size)
        if (totalSize == 0) return 0.0 to gradient
        val scale = L2_WEIGHT * batchSize.toDouble() / totalSize
        var sum = 0.0
        for (i in weights.indices) {
            val sigma = PARAMS_STDDEV[i]
            val denominator = sigma * sigma
            val difference = weights[i] - target[i]
            sum += difference * difference / denominator
            gradient[i] = 2.0 * difference / denominator * scale
        }
        return sum * scale to gradient
    }

    /**
     * Central-difference gradient of the batch loss plus the L2 term. The 68
     * evaluations are independent, so they fan out across the default
     * dispatcher. Each evaluation checks for cancellation so a cancelled fit
     * stops inside this fan-out instead of after several more replays.
     */
    private suspend fun gradient(
        weights: DoubleArray,
        batch: List<CardTerms>,
        prepared: List<PreparedCard>,
        target: DoubleArray,
        totalSize: Int
    ): DoubleArray = coroutineScope {
        val chunks = (weights.indices).chunked(6)
        val parts = chunks.map { chunk ->
            async(Dispatchers.Default) {
                val results = ArrayList<Pair<Int, Double>>(chunk.size)
                for (index in chunk) {
                    val step = FD_STEP * (1.0 + abs(weights[index]))
                    val up = weights.copyOf().also { it[index] += step }
                    val down = weights.copyOf().also { it[index] -= step }
                    results += index to
                        ((evaluateBatchLoss(up, prepared, batch).sum -
                            evaluateBatchLoss(down, prepared, batch).sum) /
                            (2.0 * step))
                    coroutineContext.ensureActive()
                }
                results
            }
        }.awaitAll()
        val gradient = DoubleArray(weights.size)
        parts.forEach { part -> part.forEach { (index, value) -> gradient[index] = value } }
        val batchSize = batch.sumOf { it.positions.size }
        val (_, l2) = l2Penalty(weights, target, batchSize, totalSize)
        for (index in gradient.indices) gradient[index] += l2[index]
        gradient
    }

    private class Adam(size: Int) {
        private val first = DoubleArray(size)
        private val second = DoubleArray(size)
        private var time = 0

        fun step(weights: DoubleArray, gradient: DoubleArray, learningRate: Double) {
            time += 1
            val firstCorrection = 1.0 - ADAM_BETA_1.pow(time)
            val secondCorrection = 1.0 - ADAM_BETA_2.pow(time)
            for (i in weights.indices) {
                first[i] = ADAM_BETA_1 * first[i] + (1.0 - ADAM_BETA_1) * gradient[i]
                second[i] = ADAM_BETA_2 * second[i] + (1.0 - ADAM_BETA_2) * gradient[i] * gradient[i]
                weights[i] -= learningRate * (first[i] / firstCorrection) /
                    (sqrt(second[i] / secondCorrection) + ADAM_EPSILON)
            }
        }
    }

    /**
     * Box clamp plus FSRS-7's cross-parameter constraints, applied after every
     * Adam step and by [clip].
     */
    private fun clipInPlace(weights: DoubleArray) {
        val defaults = FsrsScheduler.DEFAULT_FSRS_PARAMETERS
        for (i in weights.indices) {
            // `NaN.coerceIn(a, b)` is NaN: neither comparison holds, so a NaN
            // would survive a bare box clamp and poison every later step. The
            // shipped default is the deterministic, in-bounds replacement;
            // infinities are already handled by the clamp itself.
            if (weights[i].isNaN()) weights[i] = defaults[i]
            weights[i] = weights[i].coerceIn(LOWER_BOUNDS[i], UPPER_BOUNDS[i])
        }
        // Initial stabilities must not invert: the better the grade, the longer
        // the first interval. A raw Adam step can cross them.
        weights[1] = max(weights[1], weights[0])
        weights[2] = max(weights[2], weights[1])
        weights[3] = max(weights[3], weights[2])
        // The slow curve's anchor retention must sit above the fast one's.
        weights[26] = max(weights[26], max(weights[25], 0.5))
    }

    // --- loss --------------------------------------------------------------

    /** The unnormalized weighted BCE loss and its total weight. */
    private class BatchLoss(val sum: Double, val denominator: Double) {
        /** The mean loss — what the UI reports; never the training objective. */
        val mean: Double get() = if (denominator == 0.0) 0.0 else sum / denominator
    }

    /**
     * The recency-weighted log loss over [terms]: replays each card through
     * [FsrsScheduler] at its own fractional gaps and scores the predicted
     * recall before every predicted review.
     *
     * [BatchLoss.sum] is the **unnormalized** weighted sum upstream trains on.
     * Dividing it by weight here would leave the upstream-scaled L2 term
     * disproportionately strong, so the mean is computed only for reporting.
     */
    private fun evaluateBatchLoss(
        parameters: DoubleArray,
        prepared: List<PreparedCard>,
        terms: List<CardTerms>
    ): BatchLoss {
        if (terms.isEmpty()) return BatchLoss(0.0, 0.0)
        val scheduler = FsrsScheduler(
            parameters = parameters.toList(),
            desiredRetention = FsrsScheduler.DEFAULT_DESIRED_RETENTION,
            config = SchedulerConfig(),
            zoneOffset = { 0L }
        )
        var numerator = 0.0
        var denominator = 0.0
        for (cardTerms in terms) {
            val card = prepared[cardTerms.card]
            var state = card.initialState()
            var term = 0
            var pos = 0
            while (term < cardTerms.positions.size) {
                if (pos == cardTerms.positions[term]) {
                    val predicted = scheduler.recallAt(state, card.deltas[pos])
                        .coerceIn(PROBABILITY_FLOOR, PROBABILITY_CEILING)
                    val label = cardTerms.labels[term]
                    val weight = cardTerms.weights[term]
                    numerator += weight * (label * ln(predicted) + (1.0 - label) * ln(1.0 - predicted))
                    denominator += weight
                    term += 1
                    if (term == cardTerms.positions.size) break
                }
                state = scheduler.advanceMemory(state, card.ratings[pos], card.deltas[pos])
                pos += 1
            }
        }
        // The running numerator accumulates log-likelihood (negative loss); the
        // training objective is its negation, unnormalized, exactly as upstream
        // sums it.
        return BatchLoss(-numerator, denominator)
    }
}
