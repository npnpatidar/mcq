package com.mcqapp.domain.signals

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.Difficulty
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Which counter set a ranking reads (stats.md §9.1/§9.2).
 *
 * A representative mock must not be contaminated by practice, so measurement
 * reads the `rep*` counters. Personal focus rankings deliberately allow every
 * attempt, because practice evidence is exactly what they are trying to use.
 */
enum class CounterSet {
    REPRESENTATIVE,
    PERSONAL
}

/**
 * The read-only part of a `card_state` row that the signals need.
 *
 * [contentHashMatches] is computed by the caller from the card and the current
 * question. A stale hash makes the memory estimate invalid: the card is treated
 * as new even though the row still exists.
 */
data class CardSignal(
    /** FSRS difficulty, 1..10. */
    val difficulty: Double,
    /** Days until recall probability falls to the benchmark retention. */
    val stability: Double,
    /** Total FSRS reviews ever applied. */
    val reviewCount: Int,
    val lapses: Int,
    val leech: Boolean,
    val dueAt: Long,
    val lastReviewedAt: Long,
    val contentHashMatches: Boolean,
    val state: CardStateKind = CardStateKind.NEW
)

/**
 * One question's raw evidence for the signal calculations.
 *
 * The counters are the all-attempt `question_stats` counters; the `rep*`
 * counters are the representative subset. A caller that has no row passes
 * zeros and a null [card], which yields the neutral priors below rather than a
 * fabricated easy/hard label.
 */
data class QuestionSignalInput(
    val questionId: String,
    val categoryId: String? = null,
    val categoryTitle: String = "",
    /** The author's label, or null when the bank has none. */
    val authoredDifficulty: Difficulty? = null,
    val correct: Int = 0,
    val wrong: Int = 0,
    val skip: Int = 0,
    val repCorrect: Int = 0,
    val repWrong: Int = 0,
    val repSkip: Int = 0,
    /** Reviews whose `wasGuess` flag is true. */
    val guesses: Int = 0,
    /** Reviews that carry a non-null `wasGuess` flag; pre-v19 rows are ignored. */
    val guessFlags: Int = 0,
    val card: CardSignal? = null,
    /**
     * Predicted recall probability for a valid card, or null when the caller
     * has no scheduler or the card is invalid. Null keeps `memoryRisk` neutral.
     */
    val retention: Double? = null
)

/**
 * The derived, comparable signals for one question (stats.md §9.2).
 *
 * Every value is in `0..1` and higher means "more of the named property":
 * [weakness], [skip], [guess], [lapse], [difficulty] and [memoryRisk] are bad
 * for the learner; [ease] is good. [toughness] is the composite that ranks
 * Toughest questions.
 */
data class Signals(
    val questionId: String,
    val categoryId: String?,
    val categoryTitle: String,
    /** The author's label, or null when the bank has none. */
    val authoredDifficulty: Difficulty?,
    /** `correct + wrong` for the chosen counter set. */
    val answered: Int,
    /** `correct + wrong + skip` for the chosen counter set. */
    val graded: Int,
    val correct: Int,
    val repAnswered: Int,
    val repGraded: Int,
    val repCorrect: Int,
    val weakness: Double,
    val skip: Double,
    val guess: Double,
    val lapse: Double,
    /** Confidence-weighted FSRS/authored prior. */
    val difficulty: Double,
    val memoryRisk: Double,
    val unseen: Boolean,
    val stale: Boolean,
    val fsrsAvailable: Boolean,
    val leech: Boolean,
    val atRisk: Boolean,
    /** The card's due instant, or 0 when there is no valid card. */
    val dueAt: Long,
    /** When the card was last reviewed, or 0 when it has no history. */
    val lastReviewedAt: Long,
    /** Predicted recall probability for a valid card, or null. */
    val retention: Double?,
    val toughness: Double,
    val ease: Double,
    /** The tricky composite that ranks leeches, lapses, guesses and skips. */
    val trickiness: Double
)

/**
 * One category's aggregate, used by Weakest topics (stats.md §9.2/§9.3).
 *
 * [weakness] is the category composite; [questionCount] is the supply the
 * allocator can draw from, including questions with no evidence (they carry
 * neutral priors in the means, which is why an unseen category is not ranked
 * "easy").
 */
data class CategorySignals(
    val categoryId: String,
    val categoryTitle: String,
    val questionCount: Int,
    val answered: Int,
    val correct: Int,
    val weakness: Double,
    val meanDifficulty: Double,
    val meanMemoryRisk: Double,
    val meanLapse: Double
)

/**
 * The pure per-question signal rules (stats.md §9.2). No database, no clock and
 * no Compose: every branch is JVM-testable.
 */
object QuestionSignals {

    /** The z used by every Wilson interval in the report and the rankings. */
    const val Z: Double = 1.96

    /** Below this many answers the observed rate is not used. */
    const val MIN_ANSWERS: Int = 3

    /** Reviews needed before the FSRS difficulty is trusted fully. */
    const val FULL_DIFFICULTY_EVIDENCE: Double = 5.0

    private const val NEUTRAL: Double = 0.5

    /** Wilson score interval lower bound for a binomial proportion. */
    fun wilsonLower(proportion: Double, sample: Int, z: Double = Z): Double {
        if (sample <= 0) return 0.0
        val p = proportion.coerceIn(0.0, 1.0)
        val n = sample.toDouble()
        val denominator = 1.0 + z * z / n
        val half = z * sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n))
        return ((p + z * z / (2.0 * n) - half) / denominator).coerceIn(0.0, 1.0)
    }

    /**
     * Derives one question's signals. [now] decides whether a valid card is
     * at risk; [retention] in the input is the scheduler's prediction for the
     * same instant.
     */
    fun of(
        input: QuestionSignalInput,
        counterSet: CounterSet = CounterSet.PERSONAL,
        now: Long = 0L
    ): Signals {
        val answered = input.correct + input.wrong
        val graded = answered + input.skip
        val repAnswered = input.repCorrect + input.repWrong
        val repGraded = repAnswered + input.repSkip

        val correct = when (counterSet) {
            CounterSet.REPRESENTATIVE -> input.repCorrect
            CounterSet.PERSONAL -> input.correct
        }
        val chosenAnswered = when (counterSet) {
            CounterSet.REPRESENTATIVE -> repAnswered
            CounterSet.PERSONAL -> answered
        }
        val weakness = if (chosenAnswered >= MIN_ANSWERS) {
            1.0 - wilsonLower(correct.toDouble() / chosenAnswered, chosenAnswered)
        } else {
            NEUTRAL
        }

        val skip = if (graded >= MIN_ANSWERS) {
            input.skip.toDouble() / graded
        } else {
            NEUTRAL
        }

        val guess = if (input.guessFlags >= MIN_ANSWERS) {
            input.guesses.toDouble() / input.guessFlags
        } else {
            0.0
        }

        val card = input.card
        val valid = card != null &&
            card.stability > 0.0 &&
            card.reviewCount > 0 &&
            card.state != CardStateKind.NEW &&
            card.contentHashMatches
        val stale = card != null && !card.contentHashMatches
        val unseen = card == null || card.state == CardStateKind.NEW || stale
        val leech = card?.leech == true

        val lapse = if (leech) {
            1.0
        } else {
            val reviews = card?.reviewCount ?: 0
            if (reviews <= 0) 0.0 else min(1.0, card!!.lapses.toDouble() / reviews * 2.0)
        }

        val authored = input.authoredDifficulty?.toSignal() ?: NEUTRAL
        val difficulty = if (valid) {
            val fsrs = ((card!!.difficulty - 1.0) / 9.0).coerceIn(0.0, 1.0)
            val evidence = min(1.0, card.reviewCount / FULL_DIFFICULTY_EVIDENCE)
            evidence * fsrs + (1.0 - evidence) * authored
        } else {
            authored
        }

        val memoryRisk = if (valid && input.retention != null) {
            (1.0 - input.retention).coerceIn(0.0, 1.0)
        } else {
            NEUTRAL
        }

        val atRisk = valid && card!!.dueAt > 0L && card.dueAt <= now

        val toughness = 0.35 * weakness +
            0.20 * difficulty +
            0.20 * memoryRisk +
            0.15 * lapse +
            0.10 * skip
        val ease = 0.45 * (1.0 - weakness) +
            0.25 * (1.0 - difficulty) +
            0.20 * (1.0 - memoryRisk) +
            0.10 * (1.0 - lapse)
        val trickiness = 0.40 * lapse +
            0.25 * guess +
            0.20 * skip +
            0.15 * weakness

        return Signals(
            questionId = input.questionId,
            categoryId = input.categoryId,
            categoryTitle = input.categoryTitle,
            authoredDifficulty = input.authoredDifficulty,
            answered = answered,
            graded = graded,
            correct = correct,
            repAnswered = repAnswered,
            repGraded = repGraded,
            repCorrect = input.repCorrect,
            weakness = weakness.coerceIn(0.0, 1.0),
            skip = skip.coerceIn(0.0, 1.0),
            guess = guess.coerceIn(0.0, 1.0),
            lapse = lapse.coerceIn(0.0, 1.0),
            difficulty = difficulty.coerceIn(0.0, 1.0),
            memoryRisk = memoryRisk.coerceIn(0.0, 1.0),
            unseen = unseen,
            stale = stale,
            fsrsAvailable = valid,
            leech = leech,
            atRisk = atRisk,
            dueAt = if (valid) card!!.dueAt else 0L,
            lastReviewedAt = if (valid) card!!.lastReviewedAt else 0L,
            retention = if (valid) input.retention else null,
            toughness = toughness.coerceIn(0.0, 1.0),
            ease = ease.coerceIn(0.0, 1.0),
            trickiness = trickiness.coerceIn(0.0, 1.0)
        )
    }

    /** `EASY = 0`, `MEDIUM = 0.5`, `HARD = 1` (stats.md §9.2). */
    private fun Difficulty.toSignal(): Double = when (this) {
        Difficulty.EASY -> 0.0
        Difficulty.MEDIUM -> 0.5
        Difficulty.HARD -> 1.0
    }

    /**
     * The category composite (stats.md §9.2). The rate term uses the lower
     * confidence bound; the other three terms are means over every question in
     * the category, so a category with no evidence carries the neutral prior
     * instead of looking easy.
     */
    fun categoryWeakness(signals: List<Signals>): CategorySignals? {
        val first = signals.firstOrNull() ?: return null
        val categoryId = first.categoryId ?: return null
        val answered = signals.sumOf { it.answered }
        val correct = signals.sumOf { it.correct }
        val rateWeakness = if (answered >= MIN_ANSWERS) {
            1.0 - wilsonLower(correct.toDouble() / answered, answered)
        } else {
            NEUTRAL
        }
        val meanDifficulty = signals.map { it.difficulty }.average()
        val meanMemoryRisk = signals.map { it.memoryRisk }.average()
        val meanLapse = signals.map { it.lapse }.average()
        val weakness = 0.50 * rateWeakness +
            0.25 * meanDifficulty +
            0.15 * meanMemoryRisk +
            0.10 * meanLapse
        return CategorySignals(
            categoryId = categoryId,
            categoryTitle = first.categoryTitle,
            questionCount = signals.size,
            answered = answered,
            correct = correct,
            weakness = weakness.coerceIn(0.0, 1.0),
            meanDifficulty = meanDifficulty,
            meanMemoryRisk = meanMemoryRisk,
            meanLapse = meanLapse
        )
    }
}
