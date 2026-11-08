package com.mcqapp

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.signals.CardSignal
import com.mcqapp.domain.signals.CounterSet
import com.mcqapp.domain.signals.QuestionSignalInput
import com.mcqapp.domain.signals.QuestionSignals
import com.mcqapp.domain.signals.Signals
import com.mcqapp.domain.signals.TestRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure signal rules (stats.md §9.2): Wilson bounds, neutral priors, the
 * FSRS validity gate, counter-set choice and the toughness/ease composites.
 */
class QuestionSignalsTest {

    private fun card(
        difficulty: Double = 5.0,
        stability: Double = 10.0,
        reviewCount: Int = 5,
        lapses: Int = 0,
        leech: Boolean = false,
        dueAt: Long = 0L,
        hash: Boolean = true,
        state: CardStateKind = CardStateKind.REVIEW
    ) = CardSignal(
        difficulty = difficulty,
        stability = stability,
        reviewCount = reviewCount,
        lapses = lapses,
        leech = leech,
        dueAt = dueAt,
        lastReviewedAt = 1L,
        contentHashMatches = hash,
        state = state
    )

    private fun input(
        id: String = "q1",
        category: String = "c1",
        title: String = "Category",
        authored: Difficulty? = null,
        correct: Int = 0,
        wrong: Int = 0,
        skip: Int = 0,
        repCorrect: Int = 0,
        repWrong: Int = 0,
        repSkip: Int = 0,
        guesses: Int = 0,
        guessFlags: Int = 0,
        card: CardSignal? = null,
        retention: Double? = null
    ) = QuestionSignalInput(
        questionId = id,
        categoryId = category,
        categoryTitle = title,
        authoredDifficulty = authored,
        correct = correct,
        wrong = wrong,
        skip = skip,
        repCorrect = repCorrect,
        repWrong = repWrong,
        repSkip = repSkip,
        guesses = guesses,
        guessFlags = guessFlags,
        card = card,
        retention = retention
    )

    @Test
    fun wilsonLowerMatchesThePublishedFormula() {
        assertEquals(0.236589, QuestionSignals.wilsonLower(0.5, 10), 1e-5)
        assertEquals(0.438494, QuestionSignals.wilsonLower(1.0, 3), 1e-5)
        assertTrue(QuestionSignals.wilsonLower(0.0, 3) < 0.01)
        assertEquals(0.0, QuestionSignals.wilsonLower(0.5, 0), 0.0)
    }

    @Test
    fun noEvidenceUsesNeutralPriors() {
        val signal = QuestionSignals.of(input(), now = 1_000L)
        assertEquals(0.5, signal.weakness, 0.0)
        assertEquals(0.5, signal.skip, 0.0)
        assertEquals(0.0, signal.guess, 0.0)
        assertEquals(0.0, signal.lapse, 0.0)
        assertEquals(0.5, signal.difficulty, 0.0)
        assertEquals(0.5, signal.memoryRisk, 0.0)
        assertTrue(signal.unseen)
        assertFalse(signal.stale)
        assertFalse(signal.fsrsAvailable)
        assertEquals(0.425, signal.toughness, 1e-9)
        assertEquals(0.55, signal.ease, 1e-9)
    }

    @Test
    fun staleHashIsTreatedAsNew() {
        val signal = QuestionSignals.of(
            input(authored = Difficulty.HARD, card = card(hash = false), retention = 0.9),
            now = 1_000L
        )
        assertTrue(signal.stale)
        assertTrue(signal.unseen)
        assertFalse(signal.fsrsAvailable)
        assertEquals(1.0, signal.difficulty, 0.0)
        assertEquals(0.5, signal.memoryRisk, 0.0)
    }

    @Test
    fun aNewCardHasNoFsrsEstimate() {
        val signal = QuestionSignals.of(
            input(
                authored = Difficulty.EASY,
                card = card(state = CardStateKind.NEW, stability = 10.0, reviewCount = 5),
                retention = 0.9
            ),
            now = 1_000L
        )
        assertTrue(signal.unseen)
        assertFalse(signal.fsrsAvailable)
        assertEquals(0.0, signal.difficulty, 0.0)
    }

    @Test
    fun aLeechHasMaximumLapse() {
        val signal = QuestionSignals.of(
            input(card = card(leech = true, lapses = 0)),
            now = 1_000L
        )
        assertTrue(signal.leech)
        assertEquals(1.0, signal.lapse, 0.0)
    }

    @Test
    fun fsrsDifficultyIsWeightedByReviewCount() {
        val one = QuestionSignals.of(
            input(
                authored = Difficulty.EASY,
                card = card(difficulty = 10.0, reviewCount = 1),
                retention = 0.9
            ),
            now = 1_000L
        )
        assertEquals(0.2, one.difficulty, 1e-9)

        val five = QuestionSignals.of(
            input(
                authored = Difficulty.EASY,
                card = card(difficulty = 10.0, reviewCount = 5),
                retention = 0.9
            ),
            now = 1_000L
        )
        assertEquals(1.0, five.difficulty, 1e-9)
    }

    @Test
    fun representativeRankingReadsTheRepresentativeCounters() {
        val personal = QuestionSignals.of(
            input(correct = 0, wrong = 10, repCorrect = 3, repWrong = 0),
            CounterSet.PERSONAL
        )
        val representative = QuestionSignals.of(
            input(correct = 0, wrong = 10, repCorrect = 3, repWrong = 0),
            CounterSet.REPRESENTATIVE
        )
        assertEquals(1.0, personal.weakness, 1e-9)
        assertEquals(0.561506, representative.weakness, 1e-5)
    }

    @Test
    fun skipAndGuessNeedThreeObservations() {
        val two = QuestionSignals.of(input(skip = 1, correct = 1, guesses = 1, guessFlags = 2))
        assertEquals(0.5, two.skip, 0.0)
        assertEquals(0.0, two.guess, 0.0)

        val three = QuestionSignals.of(input(skip = 1, correct = 2, guesses = 1, guessFlags = 3))
        assertEquals(1.0 / 3.0, three.skip, 1e-9)
        assertEquals(1.0 / 3.0, three.guess, 1e-9)
    }

    @Test
    fun atRiskRequiresAValidDueCard() {
        val due = QuestionSignals.of(
            input(card = card(dueAt = 500L), retention = 0.9),
            now = 1_000L
        )
        assertTrue(due.atRisk)

        val future = QuestionSignals.of(
            input(card = card(dueAt = 2_000L), retention = 0.9),
            now = 1_000L
        )
        assertFalse(future.atRisk)

        val stale = QuestionSignals.of(
            input(card = card(dueAt = 500L, hash = false), retention = 0.9),
            now = 1_000L
        )
        assertFalse(stale.atRisk)
    }

    @Test
    fun toughAndWeakRanksAboveEasyAndStrong() {
        val hard = QuestionSignals.of(
            input(
                correct = 0,
                wrong = 5,
                skip = 5,
                authored = Difficulty.HARD,
                card = card(difficulty = 9.0, lapses = 5, leech = true),
                retention = 0.4
            )
        )
        val easy = QuestionSignals.of(
            input(
                correct = 5,
                wrong = 0,
                authored = Difficulty.EASY,
                card = card(difficulty = 1.0, lapses = 0),
                retention = 0.99
            )
        )
        assertTrue(hard.toughness > easy.toughness)
        assertTrue(hard.ease < easy.ease)
    }

    @Test
    fun weakestCategoryUsesTheCompositeAndNeutralPrior() {
        val weak = (1..5).map {
            QuestionSignals.of(input(id = "w$it", category = "weak", title = "Weak", wrong = 5))
        }
        val strong = (1..5).map {
            QuestionSignals.of(input(id = "s$it", category = "strong", title = "Strong", correct = 5))
        }
        val ranked = TestRanking.weakestCategories(weak + strong, 2)
        assertEquals("weak", ranked.first().categoryId)
        assertEquals(0.7, ranked.first().weakness, 1e-9)

        val unseen = TestRanking.weakestCategories(
            (1..5).map { QuestionSignals.of(input(id = "u$it", category = "unseen", title = "Unseen")) },
            1
        ).single()
        assertEquals(0.45, unseen.weakness, 1e-9)
    }

    @Test
    fun easiestExcludesLeechesOnlyWhenAlternativesRemain() {
        val normal = (1..2).map {
            QuestionSignals.of(
                input(id = "n$it", category = "c", correct = 5, authored = Difficulty.EASY)
            )
        }
        val leech = QuestionSignals.of(
            input(
                id = "leech",
                category = "c",
                correct = 5,
                authored = Difficulty.EASY,
                card = card(leech = true)
            )
        )
        val ranked = TestRanking.rankEasiest(normal + leech, limit = 2)
        assertFalse(ranked.any { it.questionId == "leech" })

        val short = TestRanking.rankEasiest(normal + leech, limit = 3)
        assertTrue(short.any { it.questionId == "leech" })
    }

    @Test
    fun aSignalCarriesItsAuthoredLabelForTheFillLadder() {
        val signal = QuestionSignals.of(input(authored = Difficulty.HARD))
        assertEquals(Difficulty.HARD, signal.authoredDifficulty)
    }
}
