package com.mcqapp

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.stats.Calibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calibration estimand and its guards, without a database: pairing within
 * a paper-scoped card, the exclusion set, the adjacent contrast, the
 * card-clustered interval tiers and the block/epoch assignment.
 */
class CalibrationTest {

    private var nextId = 1L
    private val day = 86_400_000L
    private val t0 = 1_750_000_000_000L

    private fun row(
        paperId: String = "p1",
        questionId: String = "q1",
        rating: Int,
        reviewedAt: Long,
        gradeSource: String? = "AUTO",
        predictedRecall: Double? = 0.85,
        stateBefore: CardStateKind? = CardStateKind.REVIEW,
        wasGuess: Boolean? = false,
        wasCorrect: Boolean? = true,
        wasSkipped: Boolean? = false,
        intervalDaysAfter: Int? = 5
    ) = Calibration.Row(
        id = nextId++,
        paperId = paperId,
        questionId = questionId,
        rating = rating,
        reviewedAt = reviewedAt,
        gradeSource = gradeSource,
        proposedRating = null,
        ladderVersion = 1,
        fsrsFitVersion = 0,
        predictedRecall = predictedRecall,
        stateBefore = stateBefore,
        wasGuess = wasGuess,
        wasCorrect = wasCorrect,
        wasSkipped = wasSkipped,
        intervalDaysAfter = intervalDaysAfter
    )

    /** One label/outcome pair for a card. */
    private fun card(
        paperId: String = "p1",
        questionId: String = "q1",
        grade: Calibration.LadderGrade = Calibration.LadderGrade.EASY,
        outcome: Boolean = true,
        predicted: Double = 0.8,
        labelAt: Long = t0,
        outcomeAt: Long = t0 + 5 * day,
        labelSource: String = "AUTO",
        labelWasGuess: Boolean = false,
        labelWasSkipped: Boolean = false,
        newLabel: Boolean = false,
        outcomePredicted: Double? = predicted,
        outcomeCorrect: Boolean? = outcome,
        intervalDaysAfter: Int? = 5
    ): List<Calibration.Row> = listOf(
        row(
            paperId = paperId,
            questionId = questionId,
            rating = grade.rating,
            reviewedAt = labelAt,
            gradeSource = labelSource,
            stateBefore = if (newLabel) CardStateKind.NEW else CardStateKind.REVIEW,
            wasGuess = labelWasGuess,
            wasSkipped = labelWasSkipped,
            intervalDaysAfter = intervalDaysAfter
        ),
        row(
            paperId = paperId,
            questionId = questionId,
            rating = 3,
            reviewedAt = outcomeAt,
            predictedRecall = outcomePredicted,
            wasCorrect = outcomeCorrect
        )
    )

    private fun report(vararg cards: List<Calibration.Row>, now: Long = t0 + 400 * day) =
        Calibration.report(cards.flatMap { it }, now = now, epochStart = t0)

    @Test
    fun `labels before the epoch are not pooled`() {
        // The old label belongs to the previous ladder version; its outcome
        // arrives inside the epoch, which must not make it evidence.
        val old = card(
            questionId = "old",
            grade = Calibration.LadderGrade.EASY,
            outcome = false,
            labelAt = t0 - 60 * day,
            outcomeAt = t0 + day
        )
        val current = card(
            questionId = "new",
            grade = Calibration.LadderGrade.EASY,
            outcome = true,
            labelAt = t0 + 2 * day
        )
        val result = Calibration.report(
            old + current,
            now = t0 + 400 * day,
            epochStart = t0
        )
        assertEquals(1, result.pairs)
        assertEquals(0, result.excluded)
        assertEquals(t0, result.epochStart)
    }

    @Test
    fun `a refit start passed as the epoch excludes the pre-refit labels`() {
        // StatsStore rebases the epoch on the first label under the current
        // weights; the report must honour that boundary the same way it honours
        // a ladder-version change.
        val beforeRefit = card(
            questionId = "before",
            grade = Calibration.LadderGrade.EASY,
            outcome = false,
            labelAt = t0 + day,
            outcomeAt = t0 + 6 * day
        )
        val afterRefit = card(
            questionId = "after",
            grade = Calibration.LadderGrade.GOOD,
            outcome = true,
            labelAt = t0 + 10 * day,
            outcomeAt = t0 + 15 * day
        )
        val refitAt = t0 + 10 * day
        val result = Calibration.report(
            beforeRefit + afterRefit,
            now = refitAt + 400 * day,
            epochStart = refitAt
        )
        assertEquals(1, result.pairs)
        assertEquals(Calibration.LadderGrade.GOOD, result.grades.first { it.pairs > 0 }.grade)
        assertEquals(refitAt, result.blocks.single().startAt)
    }

    @Test
    fun `pairs are adjacent reviews within one paper-scoped card`() {
        // The same question id in two papers is two cards; each pairs with its
        // own next review, never across papers.
        val result = Calibration.report(
            card(paperId = "p1", questionId = "same") +
                card(paperId = "p2", questionId = "same", outcome = false),
            now = t0 + 400 * day,
            epochStart = t0
        )
        assertEquals(2, result.pairs)
    }

    @Test
    fun `a block carries its own card counts per grade`() {
        val result = Calibration.report(
            card(questionId = "a", grade = Calibration.LadderGrade.EASY) +
                card(questionId = "b", grade = Calibration.LadderGrade.EASY) +
                card(questionId = "c", grade = Calibration.LadderGrade.GOOD),
            now = t0 + 400 * day,
            epochStart = t0
        )
        val block = result.blocks.single()
        assertEquals(2, block.easyPairs)
        assertEquals(2, block.easyCards)
        assertEquals(1, block.goodPairs)
        assertEquals(1, block.goodCards)
        assertEquals(0, block.hardCards)
    }

    @Test
    fun `delta1 is the Easy minus Good residual`() {
        // Easy: correct vs 0.9 -> +0.1. Good: incorrect vs 0.8 -> -0.8.
        val result = Calibration.report(
            card(grade = Calibration.LadderGrade.EASY, predicted = 0.9, outcome = true) +
                card(
                    paperId = "p1",
                    questionId = "q2",
                    grade = Calibration.LadderGrade.GOOD,
                    predicted = 0.8,
                    outcome = false
                ),
            now = t0 + 400 * day,
            epochStart = t0
        )
        assertEquals(2, result.pairs)
        assertEquals(0.9, result.contrast1.value!!, 1e-9)
        val easy = result.grades.first { it.grade == Calibration.LadderGrade.EASY }
        assertEquals(1.0, easy.recall!!, 1e-9)
    }

    private data class Case(
        val name: String,
        val rows: List<Calibration.Row>,
        val reason: Calibration.Exclusion
    )

    @Test
    fun `each exclusion removes its pair`() {
        fun mutate(
            reason: Calibration.Exclusion,
            update: (label: Calibration.Row, outcome: Calibration.Row) -> Pair<Calibration.Row, Calibration.Row>
        ): Case {
            val base = card()
            val (label, outcome) = update(base[0], base[1])
            return Case(reason.name, listOf(label, outcome), reason)
        }

        val cases = listOf(
            mutate(Calibration.Exclusion.LABEL_NOT_AUTO) { l, o -> l.copy(gradeSource = "CHOSEN") to o },
            mutate(Calibration.Exclusion.LABEL_AGAIN) { l, o -> l.copy(rating = 1) to o },
            mutate(Calibration.Exclusion.LABEL_GUESS) { l, o -> l.copy(wasGuess = true) to o },
            mutate(Calibration.Exclusion.LABEL_SKIPPED) { l, o -> l.copy(wasSkipped = true) to o },
            mutate(Calibration.Exclusion.NEW_EITHER_ROW) { l, o ->
                l.copy(stateBefore = CardStateKind.NEW) to o
            },
            mutate(Calibration.Exclusion.NO_FORECAST) { l, o -> l to o.copy(predictedRecall = null) },
            mutate(Calibration.Exclusion.NO_FORECAST) { l, o -> l.copy(predictedRecall = null) to o },
            mutate(Calibration.Exclusion.NO_OUTCOME) { l, o -> l to o.copy(wasCorrect = null) }
        )
        for (case in cases) {
            val result = Calibration.report(case.rows, now = t0 + 400 * day, epochStart = t0)
            assertEquals("${case.name} must drop the pair", 0, result.pairs)
            assertEquals(
                "${case.name} must be counted",
                1,
                result.exclusions.firstOrNull { it.reason == case.reason }?.count
            )
        }
    }

    @Test
    fun `follow-up counts only labels whose outcome is due`() {
        val rows = card(intervalDaysAfter = 60)
        val early = Calibration.report(rows, now = t0 + 10 * day, epochStart = t0)
        val easyEarly = early.followUp.first { it.grade == Calibration.LadderGrade.EASY }
        assertEquals("not due yet, so not a miss", 0, easyEarly.due)

        val late = Calibration.report(rows, now = t0 + 100 * day, epochStart = t0)
        val easyLate = late.followUp.first { it.grade == Calibration.LadderGrade.EASY }
        assertEquals(1, easyLate.due)
        assertEquals(1, easyLate.observed)
        assertEquals(1.0, easyLate.rate!!, 1e-9)
    }

    @Test
    fun `follow-up below the gate is flagged`() {
        // One Easy label's outcome is back, one is not: 50% < 70%.
        val missingOutcome = row(
            questionId = "q2",
            rating = Calibration.LadderGrade.EASY.rating,
            reviewedAt = t0 + 2 * day
        )
        val result = Calibration.report(
            card(questionId = "q1") + missingOutcome,
            now = t0 + 400 * day,
            epochStart = t0
        )
        assertFalse(result.followUpGatePassed)
        assertEquals(
            0.5,
            result.followUp.first { it.grade == Calibration.LadderGrade.EASY }.rate!!,
            1e-9
        )

        // Every due label has an outcome; the terminal review is not due yet,
        // so it is pending rather than missing.
        val terminal = row(
            questionId = "q1",
            rating = 3,
            reviewedAt = t0 + 400 * day,
            intervalDaysAfter = 100
        )
        val complete = Calibration.report(
            card(questionId = "q1") + terminal,
            now = t0 + 400 * day,
            epochStart = t0
        )
        assertTrue(complete.followUpGatePassed)
    }

    @Test
    fun `first exposures are reported but never paired`() {
        val result = Calibration.report(
            card(newLabel = true, grade = Calibration.LadderGrade.EASY),
            now = t0 + 400 * day,
            epochStart = t0
        )
        assertEquals(0, result.pairs)
        assertEquals(
            1,
            result.exclusions.first { it.reason == Calibration.Exclusion.NEW_EITHER_ROW }.count
        )
        val exposure = result.firstExposure.first { it.grade == Calibration.LadderGrade.EASY }
        assertEquals(1, exposure.count)
        assertEquals(1.0, exposure.recall!!, 1e-9)
    }

    @Test
    fun `fixed 28-day blocks close after the outcome horizon`() {
        val result = Calibration.report(
            card(questionId = "q1", labelAt = t0) +
                card(questionId = "q2", labelAt = t0 + 29 * day, outcomeAt = t0 + 34 * day),
            now = t0 + 400 * day,
            epochStart = t0
        )
        val blocks = result.blocks
        assertEquals(2, blocks.size)
        assertTrue("the first 28-day window is closed", blocks[0].closed)
        assertTrue("a block starts on the epoch's 28-day grid", blocks[1].startAt == t0 + 28 * day)
    }

    @Test
    fun `closed blocks carry the delta the safety valve reads`() {
        val result = Calibration.report(
            card(questionId = "easy", grade = Calibration.LadderGrade.EASY, outcome = true) +
                card(
                    questionId = "good",
                    grade = Calibration.LadderGrade.GOOD,
                    outcome = false,
                    labelAt = t0 + 2 * day,
                    outcomeAt = t0 + 7 * day
                ),
            now = t0 + 120 * day,
            epochStart = t0
        )

        val block = result.blocks.single()
        assertTrue(block.closed)
        assertEquals(0.2 - (-0.8), block.delta1!!, 1e-9)
        assertNull("one card per class has no estimable interval", block.delta1Low)
    }

    @Test
    fun `the direction table follows the sign of delta1`() {
        fun contrast(value: Double) = Calibration.Contrast(
            value = value,
            low = value - 0.02,
            high = value + 0.02,
            method = Calibration.IntervalMethod.BCA,
            bindingCards = 50
        )
        assertEquals(Calibration.Direction.LOWER, contrast(-0.06).direction)
        assertEquals(Calibration.Direction.RAISE, contrast(0.06).direction)
        assertEquals(Calibration.Direction.NONE, contrast(0.0).direction)
        assertEquals(Calibration.Direction.NONE, contrast(-0.04).direction)
    }

    /** [cards] samples per class, one card each, with alternating residuals. */
    private fun sampleCards(grade: Calibration.LadderGrade, cards: Int): List<Calibration.Sample> =
        (1..cards).map { i ->
            // Residuals alternate -0.2 / +0.2 so both classes have variance.
            val predicted = if (i % 2 == 0) 0.7 else 0.9
            Calibration.Sample(
                paperId = "p1",
                questionId = "${grade.stored}-$i",
                grade = grade,
                outcome = if (predicted == 0.7) 0.0 else 1.0,
                predicted = predicted,
                labelReviewedAt = t0 + i
            )
        }

    @Test
    fun `the interval tier follows the smaller class`() {
        val few = Calibration.contrast(
            sampleCards(Calibration.LadderGrade.EASY, 10),
            sampleCards(Calibration.LadderGrade.GOOD, 50)
        )
        assertEquals(Calibration.IntervalMethod.NONE, few.method)
        assertNull(few.low)

        val descriptive = Calibration.contrast(
            sampleCards(Calibration.LadderGrade.EASY, 25),
            sampleCards(Calibration.LadderGrade.GOOD, 50)
        )
        assertEquals(Calibration.IntervalMethod.T, descriptive.method)
        assertNotNull(descriptive.low)

        val decision = Calibration.contrast(
            sampleCards(Calibration.LadderGrade.EASY, 45),
            sampleCards(Calibration.LadderGrade.GOOD, 50)
        )
        assertEquals(Calibration.IntervalMethod.BCA, decision.method)
        assertNotNull(decision.low)
        assertTrue(decision.low!! <= decision.value!!)
        assertTrue(decision.high!! >= decision.value!!)
    }

    @Test
    fun `the bootstrap is deterministic for a seed`() {
        val easy = sampleCards(Calibration.LadderGrade.EASY, 50)
        val good = sampleCards(Calibration.LadderGrade.GOOD, 50)
        val a = Calibration.contrast(easy, good, resamples = 500, seed = 42L)
        val b = Calibration.contrast(easy, good, resamples = 500, seed = 42L)
        assertEquals(a.low, b.low)
        assertEquals(a.high, b.high)
    }

    @Test
    fun `an all-identical class claims no interval`() {
        val easy = (1..50).map {
            Calibration.Sample("p1", "q-$it", Calibration.LadderGrade.EASY, 1.0, 0.8, t0 + it)
        }
        val good = sampleCards(Calibration.LadderGrade.GOOD, 50)
        val contrast = Calibration.contrast(easy, good)
        assertNotNull(contrast.value)
        assertNull("a degenerate class gets no false interval", contrast.low)
    }

    @Test
    fun `readiness needs volume, cards, precision, calendar and no refit`() {
        val ready = Calibration.Readiness(
            pairs = 300,
            bindingCards = 40,
            halfWidth = 0.05,
            calendarDays = 28,
            refitInEpoch = false
        )
        assertTrue(ready.ready)
        assertFalse(ready.copy(pairs = 299).ready)
        assertFalse(ready.copy(bindingCards = 39).ready)
        assertFalse(ready.copy(halfWidth = 0.051).ready)
        assertFalse(ready.copy(calendarDays = 27).ready)
        assertFalse(ready.copy(refitInEpoch = true).ready)
    }
}
