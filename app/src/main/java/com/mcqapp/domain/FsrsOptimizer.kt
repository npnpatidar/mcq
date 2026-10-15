package com.mcqapp.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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
 *    Anki import carrying memory), so [seedStability]/[seedDifficulty] hold the
 *    state recorded after an earlier review and [reviews] starts at the next
 *    one. The seed row itself contributes no prediction.
 */
data class FsrsTrainingCard(
    val reviews: List<FsrsTrainingReview>,
    val seedStability: Double? = null,
    val seedDifficulty: Double? = null,
    val seedReviewedAt: Long = 0L
)

/**
 * The outcome of one [FsrsOptimizer.fit] run.
 *
 * @param parameters the fitted 21 weights, already clipped to the official
 *   FSRS-6 bounds and ready to persist.
 * @param predictions how many inter-day review prefixes the loss was measured
 *   and trained on (every surviving prefix that ends at a ≥ 1 day review and
 *   fits the sequence cap).
 * @param items every surviving inter-day prefix before the sequence cap, the
 *   count the official training-size guards apply to.
 * @param lossBefore mean log loss of [currentParameters] on [predictions].
 * @param lossAfter mean log loss of [parameters] on the same predictions.
 * @param steps Adam steps executed; 0 when the data was too thin to train on
 *   and only the closed-form initial stabilities were fitted.
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
 * Fits the 21 FSRS-6 weights to a learner's own review history — the app-side
 * counterpart of Anki's "Optimize" button.
 *
 * The training recipe follows the official reference trainer
 * (open-spaced-repetition/fsrs-rs, BSD-3-Clause), whose tests pin the pieces
 * this file ports:
 *
 *  - **Data**: following the reference's Anki converter, only prefixes ending
 *    at an inter-day review (Δt ≥ 1 whole day) are prediction targets;
 *    same-day reviews exist in the replay history but are never predicted on.
 *    `delta_t` is rounded to whole days, labels are `rating > 1`, and items
 *    whose first inter-day gap belongs to an outlier bucket are dropped.
 *  - **Initial stabilities** (w0..w3): a closed-form fit of the forgetting
 *    curve to first-exposure cards (ternary search per first rating, Laplace
 *    smoothing, then [smoothAndFill] to repair missing or inverted ratings).
 *    w4..w20 start from the official defaults.
 *  - **Loss**: recency-weighted binary cross entropy of the predicted recall
 *    probability, p clamped to [1e-4, 1-1e-4], replayed through the app's own
 *    [FsrsScheduler] so training and scheduling can never disagree.
 *  - **Optimizer**: Adam (β 0.70/0.98, ε 1e-8) on a cosine-annealed learning
 *    rate of 4e-2 over 5 epochs of 512-item batches, L2 toward the initial
 *    weights, weights clipped to the official bounds after every step.
 *
 * Deliberate deviations, all documented at the code that makes them:
 *  - Gradients are central differences over the batch loss (42 evaluations a
 *    step) instead of the reference's hand-derived analytic gradients; all
 *    arithmetic is f64 where the reference trains in f32, and the batch
 *    shuffle uses Kotlin's PRNG rather than the reference's StdRng, so fitted
 *    weights are close to the reference's but not bit-identical.
 *  - Imported cards replay from their stored post-review memory (a seed)
 *    instead of an unknown earlier history; the reference has no seeds.
 *  - With no first-exposure cards at all (a purely imported library) w0..w3
 *    keep the defaults instead of failing, since seeded replays cannot train
 *    them anyway.
 */
object FsrsOptimizer {

    /**
     * Reviews required before the settings button unlocks. The official
     * guidance is ~1000 reviews for weights that are reliable; below that the
     * defaults predict better than a fit on noise.
     */
    const val MIN_REVIEWS = 1000

    internal const val LEARNING_RATE = 4e-2
    internal const val EPOCHS = 5
    internal const val BATCH_SIZE = 512
    internal const val RANDOM_SEED = 2023L
    internal const val ADAM_BETA_1 = 0.70
    internal const val ADAM_BETA_2 = 0.98
    internal const val ADAM_EPSILON = 1e-8
    internal const val L2_WEIGHT = 0.5
    internal const val MAX_SEQ_LEN = 256
    internal const val MIN_ITEMS_FOR_DEFAULTS = 8
    internal const val MIN_ITEMS_FOR_TRAINING = 64

    private const val PROBABILITY_FLOOR = 0.0001
    private const val PROBABILITY_CEILING = 0.9999
    private const val INIT_S_MIN = 0.0001
    private const val INIT_S_MAX = 100.0
    private const val FD_STEP = 1e-5
    private const val SYNTHETIC_EPOCH = 1_600_000_000_000L

    /** Per-weight σ the reference L2 penalty normalizes by (fsrs-rs training_v6). */
    private val PARAMS_STDDEV = doubleArrayOf(
        6.43, 9.66, 17.58, 27.85, 0.57, 0.28, 0.6, 0.12, 0.39, 0.18, 0.33,
        0.3, 0.09, 0.16, 0.57, 0.25, 1.03, 0.31, 0.32, 0.14, 0.27
    )

    /** Official FSRS-6 parameter bounds (fsrs-rs parameter_clipper_v6). */
    internal val LOWER_BOUNDS = doubleArrayOf(
        0.0001, 0.0001, 0.0001, 0.0001, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0, 0.001,
        0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.01, 0.1
    )
    internal val UPPER_BOUNDS = doubleArrayOf(
        100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8, 3.5,
        5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8
    )

    /** Clamps [parameters] into the official bounds, preserving order. */
    fun clip(parameters: List<Double>): List<Double> {
        require(parameters.size == FsrsScheduler.PARAMETER_COUNT) {
            "FSRS-6 needs ${FsrsScheduler.PARAMETER_COUNT} parameters, got ${parameters.size}"
        }
        return List(parameters.size) { i -> parameters[i].coerceIn(LOWER_BOUNDS[i], UPPER_BOUNDS[i]) }
    }

    /**
     * Fits [cards] to their own history and returns the result; nothing is
     * persisted here. [currentParameters] is only measured for
     * [FsrsFitResult.lossBefore] — training starts from the official recipe,
     * not from the weights the user happens to have.
     *
     * Runs on [Dispatchers.Default] internally; [onProgress] fires after each
     * Adam step and the coroutine's cancellation stops between steps.
     *
     * @throws FsrsFitInsufficientData when fewer than
     *   [MIN_ITEMS_FOR_DEFAULTS] inter-day prefixes exist.
     */
    suspend fun fit(
        cards: List<FsrsTrainingCard>,
        currentParameters: List<Double>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): FsrsFitResult {
        require(cards.isNotEmpty()) { "no review histories to fit" }
        require(currentParameters.size == FsrsScheduler.PARAMETER_COUNT) {
            "FSRS-6 needs ${FsrsScheduler.PARAMETER_COUNT} parameters, got ${currentParameters.size}"
        }
        val prepared = cards.map { prepare(it) }

        // Surviving inter-day prefixes, keyed like the reference's trainset:
        // the outlier filter drops whole cards' inter-day prefixes when their
        // first inter-day gap lands in a removed bucket (same-day-only
        // prefixes would always survive in the reference, but this app's
        // histories never make them prediction targets, so none exist here).
        val removed = computeRemovedPairs(
            prepared.filterNot { it.seeded }
                .filter { it.firstInterday >= 0 }
                .map { InitSample(it.ratings[0], it.deltas[it.firstInterday], it.ratings[it.firstInterday] > 1) }
        )
        val trainItems = ArrayList<ItemRef>()
        prepared.forEachIndexed { cardIndex, card ->
            val cardRemoved = card.firstInterday >= 0 &&
                card.deltas[card.firstInterday] in removed[card.ratings[0]]
            if (!cardRemoved) {
                for (pos in 0 until card.size) {
                    if (card.deltas[pos] >= 1) trainItems += ItemRef(cardIndex, pos)
                }
            }
        }
        if (trainItems.size < MIN_ITEMS_FOR_DEFAULTS) {
            throw FsrsFitInsufficientData(
                "Only ${trainItems.size} inter-day review prefixes found; " +
                    "at least $MIN_ITEMS_FOR_DEFAULTS needed"
            )
        }

        // The official initialization dataset: one first-inter-day prefix per
        // complete (non-seeded) card, past the outlier filter. Seeded cards
        // are excluded — their first logged review is not a first exposure.
        val initKept = prepared.filterNot { it.seeded }
            .filter { it.firstInterday >= 0 }
            .filter { it.deltas[it.firstInterday] !in removed[it.ratings[0]] }
            .map { InitSample(it.ratings[0], it.deltas[it.firstInterday], it.ratings[it.firstInterday] > 1) }

        val averageRecall = trainItems.count { prepared[it.card].ratings[it.pos] > 1 } /
            trainItems.size.toDouble()

        // Recency weights over trainset items ordered by the review they
        // predict, the reference's 0.25 + 0.75·(i/(n−1))³, assigned before
        // the sequence cap is applied.
        val weightByCard = Array(prepared.size) { card ->
            DoubleArray(prepared[card].size) { -1.0 }
        }
        val byTime = trainItems.sortedWith(
            compareBy({ prepared[it.card].times[it.pos] }, { it.card }, { it.pos })
        )
        val weightDenominator = max(byTime.size - 1, 1)
        byTime.forEachIndexed { index, item ->
            weightByCard[item.card][item.pos] =
                0.25 + 0.75 * (index.toDouble() / weightDenominator).pow(3)
        }

        val ratingCount = initKept.groupingBy { it.rating }.eachCount()
        val initialized = if (initKept.isEmpty()) {
            // Deviation: the reference fails here. An imported library has no
            // first exposures, and seeded replays cannot train w0..w3 either,
            // so the defaults are the honest answer rather than an error.
            FsrsScheduler.DEFAULT_FSRS_PARAMETERS.take(4)
        } else {
            smoothAndFill(searchInitialStabilities(initKept, averageRecall), ratingCount)
                ?.toList()
                ?: FsrsScheduler.DEFAULT_FSRS_PARAMETERS.take(4)
        }
        val initialParameters = initialized + FsrsScheduler.DEFAULT_FSRS_PARAMETERS.drop(4)

        // Loss terms: every trainset item within the reference's sequence cap.
        val lossTerms = trainItems.filter { it.pos + 1 <= MAX_SEQ_LEN }
        val totalSize = lossTerms.size
        val groupedTerms = groupTerms(lossTerms, prepared, weightByCard)
        val lossBefore = evaluateAll(currentParameters.toDoubleArray(), prepared, groupedTerms)

        // The reference returns the initialized weights untouched when the
        // trainset is thin or holds nothing beyond first reviews.
        val enoughToTrain = trainItems.size >= MIN_ITEMS_FOR_TRAINING &&
            trainItems.size != initKept.size &&
            groupedTerms.isNotEmpty()
        if (!enoughToTrain) {
            val lossAfter = evaluateAll(initialParameters.toDoubleArray(), prepared, groupedTerms)
            return FsrsFitResult(
                parameters = initialParameters,
                predictions = lossTerms.size,
                items = trainItems.size,
                cards = prepared.size,
                lossBefore = lossBefore,
                lossAfter = lossAfter,
                steps = 0
            )
        }

        val batches = lossTerms
            .sortedBy { it.pos + 1 }
            .chunked(BATCH_SIZE)
            .map { chunk -> groupTerms(chunk, prepared, weightByCard) }
        val totalSteps = batches.size * EPOCHS
        val iterations = (totalSize / BATCH_SIZE + 1) * EPOCHS

        val weights = initialParameters.toDoubleArray()
        val target = weights.clone()
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

        val smoothed = smoothAndFill(
            (0 until 4).associate { index -> (index + 1) to weights[index] }.toMutableMap(),
            ratingCount
        ) ?: weights.copyOfRange(0, 4)
        val clipped = clip(smoothed.toList() + weights.drop(4).toList()).toDoubleArray()
        val lossAfter = evaluateAll(clipped, prepared, groupedTerms)
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

    internal class InitSample(val rating: Int, val firstDelta: Int, val label: Boolean)

    private class ItemRef(val card: Int, val pos: Int)

    private class CardTerms(
        val card: Int,
        val positions: IntArray,
        val labels: DoubleArray,
        val weights: DoubleArray
    )

    private class PreparedCard(
        val ratings: IntArray,
        val deltas: IntArray,
        val times: LongArray,
        val seedStability: Double?,
        val seedDifficulty: Double?
    ) {
        val size: Int = ratings.size
        val seeded: Boolean = seedStability != null
        val firstInterday: Int
        val ts: LongArray = LongArray(size)

        init {
            firstInterday = (0 until size).firstOrNull { deltas[it] >= 1 } ?: -1
            val base = SYNTHETIC_EPOCH + (if (seeded) deltas[0] * DayBoundary.DAY_MS else 0L)
            ts[0] = base
            for (i in 1 until size) ts[i] = ts[i - 1] + deltas[i] * DayBoundary.DAY_MS
        }

        fun initialState(): CardState =
            if (seeded) {
                CardState(
                    questionId = "fsrs-fit",
                    stability = seedStability!!.coerceAtLeast(FsrsScheduler.STABILITY_MIN),
                    difficulty = seedDifficulty!!.coerceIn(
                        FsrsScheduler.MIN_DIFFICULTY,
                        FsrsScheduler.MAX_DIFFICULTY
                    ),
                    lastReviewedAt = SYNTHETIC_EPOCH,
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
        val deltas = IntArray(size)
        if (seeded) {
            deltas[0] = roundDays(card.reviews[0].reviewedAt - card.seedReviewedAt)
        }
        for (i in 1 until size) {
            deltas[i] = roundDays(card.reviews[i].reviewedAt - card.reviews[i - 1].reviewedAt)
        }
        val times = LongArray(size) { card.reviews[it].reviewedAt }
        return PreparedCard(ratings, deltas, times, card.seedStability, card.seedDifficulty)
    }

    private fun roundDays(millis: Long): Int =
        Math.round(millis.coerceAtLeast(0L) / DayBoundary.DAY_MS.toDouble()).toInt()

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

    // --- outlier filter (fsrs-rs dataset.rs) ------------------------------

    /**
     * Drops the tail of first-exposure groups: walking each first rating's
     * buckets from the smallest, everything up to a 5% (min 20) quota goes,
     * and past the quota only groups of at least six with a first re-review
     * within 100 days (365 for Easy) survive.
     */
    internal fun computeRemovedPairs(samples: List<InitSample>): Array<HashSet<Int>> {
        val removed = Array(5) { HashSet<Int>() }
        for ((rating, group) in samples.groupBy { it.rating }) {
            val subGroups = group.groupingBy { it.firstDelta }.eachCount()
                .toList()
                .sortedWith(
                    compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first }
                )
            val total = subGroups.sumOf { it.second }
            var hasRemoved = 0
            for ((delta, size) in subGroups.asReversed()) {
                if (hasRemoved + size >= max(20, total / 20)) {
                    if (size < 6 || delta > (if (rating == 4) 365 else 100)) {
                        removed[rating].add(delta)
                    }
                } else {
                    hasRemoved += size
                    removed[rating].add(delta)
                }
            }
        }
        return removed
    }

    // --- initial stabilities (fsrs-rs parameter_initialization) -----------

    /**
     * Fits w0..w3 by searching each first rating's initial stability against
     * the observed recall of its first re-reviews: ternary search over
     * [0.0001, 100] of the count-weighted log loss plus an L1 pull of |s−default|/16.
     */
    internal fun searchInitialStabilities(
        samples: List<InitSample>,
        averageRecall: Double
    ): MutableMap<Int, Double> {
        val result = HashMap<Int, Double>()
        for ((rating, group) in samples.groupBy { it.rating }) {
            val byDelta = group.groupingBy { it.firstDelta }
                .fold(0 to 0) { acc, sample ->
                    (acc.first + if (sample.label) 1 else 0) to (acc.second + 1)
                }
                .toList()
                .sortedBy { it.first }
            val default = FsrsScheduler.DEFAULT_FSRS_PARAMETERS[rating - 1].toDouble()
            var low = INIT_S_MIN
            var high = INIT_S_MAX
            var optimal = default
            var iterations = 0
            // The reference terminates on f64::EPSILON; 1e-12 absolute is far
            // tighter than the 1e-4 the reference's own tests assert.
            while (high - low > 1e-12 && iterations < 1000) {
                iterations += 1
                val mid1 = low + (high - low) / 3.0
                val mid2 = high - (high - low) / 3.0
                val loss1 = initialStabilityLoss(byDelta, averageRecall, mid1, default)
                val loss2 = initialStabilityLoss(byDelta, averageRecall, mid2, default)
                if (loss1 < loss2) high = mid2 else low = mid1
                optimal = (high + low) / 2.0
            }
            result[rating] = optimal
        }
        return result
    }

    private fun initialStabilityLoss(
        data: List<Pair<Int, Pair<Int, Int>>>,
        averageRecall: Double,
        initialStability: Double,
        default: Double
    ): Double {
        val decay = -FsrsScheduler.DEFAULT_FSRS_PARAMETERS[20]
        val factor = 0.9.pow(1.0 / decay) - 1.0
        var sum = 0.0
        for ((delta, counts) in data) {
            val (positives, count) = counts
            val recall = (positives + averageRecall) / (count + 1.0)
            val predicted = (1.0 + factor * delta / initialStability).pow(decay)
            sum += -(recall * ln(predicted) + (1.0 - recall) * ln(1.0 - predicted)) * count
        }
        return sum + abs(initialStability - default) / 16.0
    }

    /**
     * Repairs fitted initial stabilities: forces the rating order monotonic
     * (the better-observed rating keeps its value), geometrically fills
     * ratings that were never observed, and clamps into the official range.
     * Returns null when nothing was observed at all.
     */
    internal fun smoothAndFill(
        stability: MutableMap<Int, Double>,
        counts: Map<Int, Int>
    ): DoubleArray? {
        stability.keys.retainAll(counts.keys)
        for ((small, big) in listOf(1 to 2, 2 to 3, 3 to 4, 1 to 3, 2 to 4, 1 to 4)) {
            val smallValue = stability[small]
            val bigValue = stability[big]
            if (smallValue != null && bigValue != null && smallValue > bigValue) {
                if (counts.getValue(small) > counts.getValue(big)) {
                    stability[big] = smallValue
                } else {
                    stability[small] = bigValue
                }
            }
        }
        val w1 = 0.41
        val w2 = 0.54
        val defaults = FsrsScheduler.DEFAULT_FSRS_PARAMETERS
        when (stability.size) {
            0 -> return null
            1 -> {
                val rating = stability.keys.first()
                val factor = stability.getValue(rating) / defaults[rating - 1]
                return (1..4).map { defaults[it - 1] * factor }
                    .sorted()
                    .toDoubleArray()
            }
            else -> {}
        }
        var r1 = stability[1]
        var r2 = stability[2]
        var r3 = stability[3]
        var r4 = stability[4]
        when {
            r1 == null && r2 == null && r3 != null && r4 != null -> {
                r2 = r3.pow(1.0 / (1.0 - w2)) * r4.pow(1.0 - 1.0 / (1.0 - w2))
                r1 = r2.pow(1.0 / w1) * r3.pow(1.0 - 1.0 / w1)
            }
            r1 == null && r2 != null && r3 == null && r4 != null -> {
                r3 = r2.pow(1.0 - w2) * r4.pow(w2)
                r1 = r2.pow(1.0 / w1) * r3.pow(1.0 - 1.0 / w1)
            }
            r1 == null && r2 != null && r3 != null && r4 == null -> {
                r4 = r2.pow(1.0 - 1.0 / (1.0 - w2)) * r3.pow(1.0 / (1.0 - w2))
                r1 = r2.pow(1.0 / w1) * r3.pow(1.0 - 1.0 / w1)
            }
            r1 != null && r2 == null && r3 == null && r4 != null -> {
                val exponent = w1 / (w1 + w2 - w1 * w2)
                r2 = r1.pow(exponent) * r4.pow(1.0 - exponent)
                r3 = r1.pow(1.0 - w2 / (w1 + w2 - w1 * w2)) * r4.pow(w2 / (w1 + w2 - w1 * w2))
            }
            r1 != null && r2 == null && r3 != null && r4 == null -> {
                r2 = r1.pow(w1) * r3.pow(1.0 - w1)
                r4 = r2.pow(1.0 - 1.0 / (1.0 - w2)) * r3.pow(1.0 / (1.0 - w2))
            }
            r1 != null && r2 != null && r3 == null && r4 == null -> {
                r3 = r1.pow(1.0 - 1.0 / (1.0 - w1)) * r2.pow(1.0 / (1.0 - w1))
                r4 = r2.pow(1.0 - 1.0 / (1.0 - w2)) * r3.pow(1.0 / (1.0 - w2))
            }
            r1 == null && r2 != null && r3 != null && r4 != null -> {
                r1 = r2.pow(1.0 / w1) * r3.pow(1.0 - 1.0 / w1)
            }
            r1 != null && r2 == null && r3 != null && r4 != null -> {
                r2 = r1.pow(w1) * r3.pow(1.0 - w1)
            }
            r1 != null && r2 != null && r3 == null && r4 != null -> {
                r3 = r2.pow(1.0 - w2) * r4.pow(w2)
            }
            r1 != null && r2 != null && r3 != null && r4 == null -> {
                r4 = r2.pow(1.0 - 1.0 / (1.0 - w2)) * r3.pow(1.0 / (1.0 - w2))
            }
        }
        val filled = listOfNotNull(r1, r2, r3, r4)
        if (filled.size != 4) return null
        return filled.map { it.coerceIn(INIT_S_MIN, INIT_S_MAX) }.toDoubleArray()
    }

    // --- training ----------------------------------------------------------

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
     * Central-difference gradient of the batch loss plus the L2 term. The 42
     * evaluations are independent, so they fan out across the default
     * dispatcher.
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
                chunk.map { index ->
                    val step = FD_STEP * (1.0 + abs(weights[index]))
                    val up = weights.copyOf().also { it[index] += step }
                    val down = weights.copyOf().also { it[index] -= step }
                    index to (evaluateBatch(up, prepared, batch) - evaluateBatch(down, prepared, batch)) /
                        (2.0 * step)
                }
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

    private fun clipInPlace(weights: DoubleArray) {
        for (i in weights.indices) {
            weights[i] = weights[i].coerceIn(LOWER_BOUNDS[i], UPPER_BOUNDS[i])
        }
    }

    // --- loss --------------------------------------------------------------

    private fun evaluateAll(
        parameters: DoubleArray,
        prepared: List<PreparedCard>,
        terms: List<CardTerms>
    ): Double {
        if (terms.isEmpty()) return 0.0
        return evaluateBatch(parameters, prepared, terms)
    }

    /**
     * Mean recency-weighted log loss over [terms]: replays each card through
     * [FsrsScheduler] on a synthetic integer-day timeline (so elapsed days are
     * exactly the rounded deltas the reference trains on) and scores the
     * predicted recall before every predicted review.
     */
    private fun evaluateBatch(
        parameters: DoubleArray,
        prepared: List<PreparedCard>,
        terms: List<CardTerms>
    ): Double {
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
                    val predicted = scheduler.retention(state, card.ts[pos])
                        .coerceIn(PROBABILITY_FLOOR, PROBABILITY_CEILING)
                    val label = cardTerms.labels[term]
                    val weight = cardTerms.weights[term]
                    numerator += weight * (label * ln(predicted) + (1.0 - label) * ln(1.0 - predicted))
                    denominator += weight
                    term += 1
                    if (term == cardTerms.positions.size) break
                }
                state = scheduler.next(state, gradeOf(card.ratings[pos]), card.ts[pos])
                pos += 1
            }
        }
        return if (denominator == 0.0) 0.0 else -numerator / denominator
    }

    private fun gradeOf(rating: Int): ReviewGrade = ReviewGrade.entries[rating - 1]
}
