package com.mcqapp

import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.Difficulty
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestType
import com.mcqapp.domain.signals.Breadth
import com.mcqapp.domain.signals.CardSignal
import com.mcqapp.domain.signals.CategorySignals
import com.mcqapp.domain.signals.Composition
import com.mcqapp.domain.signals.FillSource
import com.mcqapp.domain.signals.QuestionSignalInput
import com.mcqapp.domain.signals.QuestionSignals
import com.mcqapp.domain.signals.Selection
import com.mcqapp.domain.signals.Signals
import com.mcqapp.domain.signals.TestRanking
import com.mcqapp.domain.signals.TestSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phase-2 focus selectors (stats.md §8.3–§8.5, §9.3–§9.7): allocation,
 * the category cap, the fill ladder, targets and the composition codec.
 */
class TestSelectorTest {

    private fun signal(
        id: String,
        category: String,
        title: String = category,
        correct: Int = 0,
        wrong: Int = 0,
        authored: Difficulty? = null,
        leech: Boolean = false,
        dueAt: Long = 0L,
        card: CardSignal? = CardSignal(
            difficulty = 5.0,
            stability = 10.0,
            reviewCount = 5,
            lapses = 0,
            leech = leech,
            dueAt = dueAt,
            lastReviewedAt = 1L,
            contentHashMatches = true,
            state = CardStateKind.REVIEW
        )
    ): Signals = QuestionSignals.of(
        QuestionSignalInput(
            questionId = id,
            categoryId = category,
            categoryTitle = title,
            authoredDifficulty = authored,
            correct = correct,
            wrong = wrong,
            card = card,
            retention = 0.9
        ),
        now = 1_000L
    )

    // ------------------------------------------------------------------
    // Phase 3 custom selectors (stats-phase3.md).
    // ------------------------------------------------------------------

    @Test
    fun aBookmarkedSelectionSamplesFromThePoolAndFillsRandomly() {
        val signals = (0 until 10).map { signal("q$it", "c1") }
        val selection = TestSelector.select(
            plan(TestType.BOOKMARKED, 6),
            signals,
            customPool = setOf("q1", "q2", "q3")
        )!!
        assertEquals(6, selection.ids.size)
        assertTrue(selection.ids.all { it in signals.map { s -> s.questionId } })
        // Deterministic for the seed.
        val again = TestSelector.select(
            plan(TestType.BOOKMARKED, 6),
            signals,
            customPool = setOf("q1", "q2", "q3")
        )!!
        assertEquals(selection.ids, again.ids)
        // The pool is smaller than the request, so fill happens.
        assertEquals(3, selection.composition.segments.first().count)
        assertEquals("BOOKMARKED", selection.composition.segments.first().source)
        assertEquals("RANDOM", selection.composition.segments.last().source)
    }

    @Test
    fun aBookmarkedSelectionWithToSmallPoolFillsRandomly() {
        val signals = (0 until 10).map { signal("q$it", "c1") }
        val selection = TestSelector.select(
            plan(TestType.BOOKMARKED, 6),
            signals,
            customPool = setOf("q1", "q2")
        )!!
        assertEquals(6, selection.ids.size)
        assertEquals(2, selection.composition.segments.first().count)
    }

    @Test
    fun anEmptyCustomPoolFillsEntirelyRandomly() {
        val signals = (0 until 10).map { signal("q$it", "c1") }
        val selection = TestSelector.select(
            plan(TestType.TAG, 4),
            signals,
            customPool = emptySet()
        )!!
        assertEquals(4, selection.ids.size)
        // Nothing from the pool, so the composition is a pure random fill.
        assertEquals(listOf("RANDOM"), selection.composition.segments.map { it.source })
    }

    @Test
    fun anAuthoredDifficultySelectionUsesTheChosenLabel() {
        val signals = (0 until 10).map { index ->
            signal(
                "q$index", "c1",
                authored = when (index % 3) {
                    0 -> Difficulty.EASY
                    1 -> Difficulty.MEDIUM
                    else -> Difficulty.HARD
                }
            )
        }
        val easyIds = signals.filter { it.authoredDifficulty == Difficulty.EASY }.map { it.questionId }
        val selection = TestSelector.select(
            plan(TestType.AUTHORED_DIFFICULTY, 3).copy(variant = "EASY"),
            signals
        )!!
        assertEquals(3, selection.ids.size)
        assertTrue("selection must come from the chosen label: ${selection.ids}", selection.ids.all { it in easyIds })
        assertEquals("AUTHORED_EASY", selection.composition.segments.first().source)
    }

    @Test
    fun anAuthoredDifficultySelectionFillsFromAdjacentLabel() {
        val signals = (0 until 10).map { index ->
            signal(
                "q$index", "c1",
                authored = when (index) {
                    0 -> Difficulty.HARD
                    else -> Difficulty.MEDIUM
                }
            )
        }
        // Only one HARD; the other two must come from MEDIUM (the adjacent label).
        val selection = TestSelector.select(
            plan(TestType.AUTHORED_DIFFICULTY, 3).copy(variant = "HARD"),
            signals
        )!!
        assertEquals(3, selection.ids.size)
        val sources = selection.composition.segments.map { it.source }
        assertEquals("AUTHORED_HARD", sources.first())
        assertTrue("adjacent fill must be from MEDIUM: $sources", "AUTHORED_MEDIUM" in sources)
    }

    @Test
    fun anAuthoredDifficultySelectionWithoutVariantIsEmpty() {
        val signals = (0 until 10).map { signal("q$it", "c1", authored = Difficulty.EASY) }
        val selection = TestSelector.select(
            plan(TestType.AUTHORED_DIFFICULTY, 4),
            signals
        )!!
        assertEquals(0, selection.ids.size)
    }

    @Test
    fun aSlowestSelectionTakesTheTopPercentileSlowest() {
        val signals = (0 until 20).map { signal("q$it", "c1") }
        val dwell = (0 until 20).associate { "q$it" to (it + 1) * 10L }
        val selection = TestSelector.select(
            plan(TestType.SLOWEST, 5),
            signals,
            medianDwellSeconds = dwell
        )!!
        // Medians are 10..200; the top 25% are 160..200 (5 questions).
        val expected = setOf("q15", "q16", "q17", "q18", "q19")
        assertEquals(expected, selection.ids.toSet())
        assertEquals("SLOWEST", selection.composition.segments.first().source)
    }

    @Test
    fun aSlowestSelectionSupportsSmallEligibleSets() {
        val signals = (0 until 10).map { signal("q$it", "c1") }
        val dwell = mapOf("q7" to 55L, "q3" to 90L)
        val selection = TestSelector.select(
            plan(TestType.SLOWEST, 20),
            signals,
            medianDwellSeconds = dwell
        )!!
        // The 75th percentile of {55, 90} is 55 at index floor(0.75) = 0 -> 55,
        // so both are eligible; the rest is Toughest/Random fill.
        assertEquals(10, selection.ids.size)
        assertTrue(selection.ids.containsAll(listOf("q7", "q3")))
    }

    @Test
    fun aSlowestSelectionWithNoDwellIsEmpty() {
        val signals = (0 until 10).map { signal("q$it", "c1") }
        val selection = TestSelector.select(
            plan(TestType.SLOWEST, 5),
            signals,
            medianDwellSeconds = emptyMap()
        )!!
        assertEquals(0, selection.ids.size)
    }

    @Test
    fun previewReportsTheCustomPool() {
        val signals = (0 until 10).map { signal("q$it", "c1") }
        val preview = TestSelector.preview(
            plan(TestType.BOOKMARKED, 4),
            signals,
            customPool = setOf("q1", "q2", "q3")
        )
        assertEquals(3, preview.pool)
    }

    private fun category(id: String, count: Int, title: String = id): CategorySignals =
        CategorySignals(
            categoryId = id,
            categoryTitle = title,
            questionCount = count,
            answered = 0,
            correct = 0,
            weakness = 0.5,
            meanDifficulty = 0.5,
            meanMemoryRisk = 0.5,
            meanLapse = 0.0
        )

    private fun plan(
        type: TestType,
        count: Int,
        seed: Long = 7L
    ) = TestPlan(
        paperId = "p1",
        type = type,
        count = count,
        seed = seed,
        scopeKey = ScopeKey.wholePaper("p1")
    )

    @Test
    fun largestRemainderMatchesTheSpecExample() {
        val allocation = TestSelector.allocate(
            20,
            listOf(category("a", 600), category("b", 300), category("c", 100))
        )
        assertEquals(mapOf("a" to 12, "b" to 6, "c" to 2), allocation)
    }

    @Test
    fun eightEqualCategoriesSplitThreeThreeTwo() {
        val categories = (1..8).map { category("c$it", 100) }
        val allocation = TestSelector.allocate(20, categories)
        assertEquals(20, allocation.values.sum())
        assertEquals(4, allocation.values.count { it == 3 })
        assertEquals(4, allocation.values.count { it == 2 })
    }

    @Test
    fun allocationNeverExceedsASmallCategory() {
        val allocation = TestSelector.allocate(
            10,
            listOf(category("small", 2), category("large", 100))
        )
        assertEquals(1, allocation["small"])
        assertEquals(9, allocation["large"])
    }

    @Test
    fun weakestTopicsUsesTheNarrowBreadthAndRecordsTargets() {
        val signals = buildList {
            repeat(10) { add(signal("a$it", "a", correct = 0, wrong = 5)) }
            repeat(10) { add(signal("b$it", "b", correct = 5)) }
            repeat(10) { add(signal("c$it", "c", correct = 5)) }
        }
        val selection = TestSelector.select(
            plan(TestType.WEAKEST_TOPICS, 6),
            signals,
            Breadth.NARROW
        )
        assertNotNull(selection)
        assertEquals(6, selection!!.ids.size)
        assertEquals(listOf("a"), selection.targets)
        assertEquals(1, selection.composition.segments.size)
        assertEquals(FillSource.WEAKEST_TOPICS.name, selection.composition.segments.first().source)
        assertEquals(6, selection.composition.segments.first().count)
        assertTrue(selection.ids.all { it.startsWith("a") })
    }

    @Test
    fun weakestTopicsAddsTheNextCategoryWhenTheTopOneCannotFill() {
        val signals = buildList {
            repeat(2) { add(signal("a$it", "a", correct = 0, wrong = 5)) }
            repeat(10) { add(signal("b$it", "b", correct = 5)) }
        }
        val selection = TestSelector.select(
            plan(TestType.WEAKEST_TOPICS, 5),
            signals,
            Breadth.NARROW
        )!!
        assertEquals(5, selection.ids.size)
        assertEquals(listOf("a", "b"), selection.targets)
        assertTrue(selection.ids.any { it.startsWith("a") })
        assertTrue(selection.ids.any { it.startsWith("b") })
    }

    @Test
    fun toughestQuestionsApplyTheRelaxedCategoryCap() {
        val signals = buildList {
            repeat(20) { add(signal("a$it", "a", correct = 0, wrong = 5)) }
            repeat(20) { add(signal("b$it", "b", correct = 0, wrong = 5)) }
        }
        val selection = TestSelector.select(plan(TestType.TOUGHEST_QUESTIONS, 20), signals)!!
        assertEquals(20, selection.ids.size)
        assertEquals(10, selection.ids.count { it.startsWith("a") })
        assertEquals(10, selection.ids.count { it.startsWith("b") })
        assertEquals(FillSource.TOUGHEST_QUESTIONS.name, selection.composition.segments.first().source)
    }

    @Test
    fun toughestQuestionsKeepThePriorityOrderWhenTheCapLeavesRoom() {
        val signals = buildList {
            repeat(3) { add(signal("a$it", "a", correct = 0, wrong = 5)) }
            repeat(3) { add(signal("b$it", "b", correct = 5)) }
        }
        val selection = TestSelector.select(plan(TestType.TOUGHEST_QUESTIONS, 6), signals)!!
        assertEquals(6, selection.ids.size)
        assertEquals(3, selection.ids.count { it.startsWith("a") })
        assertEquals(3, selection.ids.count { it.startsWith("b") })
    }

    @Test
    fun easiestSamplesFromTheTopTwoN() {
        val signals = (1..10).map {
            signal("q$it", "c", correct = it, wrong = 10 - it, authored = Difficulty.EASY)
        }
        val selection = TestSelector.select(plan(TestType.EASIEST_QUESTIONS, 3), signals)!!
        assertEquals(3, selection.ids.size)
        assertEquals(3, selection.ids.distinct().size)
        assertEquals(FillSource.EASIEST_QUESTIONS.name, selection.composition.segments.first().source)
    }

    @Test
    fun randomAndMistakesHaveNoFocusSelection() {
        val signals = listOf(signal("q1", "c"))
        assertNull(TestSelector.select(plan(TestType.RANDOM, 20), signals))
        assertNull(TestSelector.select(plan(TestType.MISTAKES, 20), signals))
    }

    @Test
    fun trickyRanksLeechesAheadOfOrdinaryQuestions() {
        val signals = buildList {
            repeat(4) { add(signal("leech$it", "a", leech = true)) }
            repeat(6) { add(signal("plain$it", "b")) }
        }
        val selection = TestSelector.select(plan(TestType.TRICKY, 4), signals)!!
        assertEquals(4, selection.ids.size)
        // The 40% per-category cap gives the leech category two of four
        // places; the other two go to the next-ranked category.
        assertEquals(
            "ids=${selection.ids} segments=${selection.composition.segments}",
            2,
            selection.ids.count { it.startsWith("leech") }
        )
        assertEquals(FillSource.TRICKY.name, selection.composition.segments.first().source)
        assertEquals(4, selection.composition.segments.first().count)
        assertEquals(4, selection.composition.segments.sumOf { it.count })
    }

    @Test
    fun atRiskTakesOnlyDueCardsAndFillsFromTheLadder() {
        val signals = buildList {
            repeat(2) { add(signal("due$it", "a", dueAt = 500L)) }
            repeat(4) { add(signal("later$it", "b", dueAt = 5_000L)) }
        }
        val selection = TestSelector.select(plan(TestType.AT_RISK, 4), signals)!!
        assertEquals(4, selection.ids.size)
        assertEquals(2, selection.ids.count { it.startsWith("due") })
        assertEquals(FillSource.AT_RISK.name, selection.composition.segments.first().source)
        assertEquals(2, TestSelector.poolSize(TestType.AT_RISK, signals))
    }

    @Test
    fun unseenTakesQuestionsWithoutACardAndFillsRecentlyReviewed() {
        val signals = buildList {
            repeat(3) { add(signal("new$it", "a", card = null)) }
            repeat(3) { add(signal("known$it", "b")) }
        }
        val selection = TestSelector.select(plan(TestType.UNSEEN, 3), signals)!!
        assertEquals(3, selection.ids.size)
        assertTrue(selection.ids.all { it.startsWith("new") })
        assertEquals(FillSource.UNSEEN.name, selection.composition.segments.first().source)
        assertEquals(3, TestSelector.poolSize(TestType.UNSEEN, signals))
    }

    @Test
    fun balancedAllocatesProportionallyAndRecordsNoTargets() {
        val signals = buildList {
            repeat(30) { add(signal("a$it", "a")) }
            repeat(10) { add(signal("b$it", "b")) }
        }
        val selection = TestSelector.select(plan(TestType.BALANCED, 20), signals)!!
        assertEquals(20, selection.ids.size)
        assertEquals(15, selection.ids.count { it.startsWith("a") })
        assertEquals(5, selection.ids.count { it.startsWith("b") })
        assertTrue(selection.targets.isEmpty())
        assertEquals(
            FillSource.BALANCED.name,
            selection.composition.segments.first().source
        )
        assertEquals(20, selection.composition.segments.first().count)
    }

    @Test
    fun mixedBlendsThePartsAndKeepsTheWeakestTargets() {
        val signals = buildList {
            repeat(10) { add(signal("weak$it", "a", correct = 0, wrong = 5)) }
            repeat(10) { add(signal("strong$it", "b", correct = 5)) }
            add(signal("due", "c", dueAt = 500L))
        }
        val selection = TestSelector.select(plan(TestType.MIXED, 10), signals)!!
        assertEquals(10, selection.ids.size)
        assertEquals(10, selection.ids.distinct().size)
        assertTrue("the weakest topic is a target", "a" in selection.targets)
        assertTrue("breadth caps the target list", selection.targets.size <= 3)
        val sources = selection.composition.segments.map { it.source }
        assertTrue(FillSource.WEAKEST_TOPICS.name in sources)
        assertTrue(FillSource.TRICKY.name in sources)
        assertEquals(10, selection.composition.segments.sumOf { it.count })
    }

    @Test
    fun previewReportsThePoolCompositionAndTargetAccuracy() {
        val signals = buildList {
            repeat(10) { add(signal("weak$it", "a", title = "Algebra", correct = 0, wrong = 5)) }
            repeat(10) { add(signal("strong$it", "b", correct = 5)) }
        }
        val preview = TestSelector.preview(
            plan(TestType.WEAKEST_TOPICS, 10),
            signals,
            Breadth.NARROW
        )
        assertEquals(20, preview.pool)
        assertEquals(10, preview.composition.segments.sumOf { it.count })
        assertEquals("Algebra", preview.targets.single().title)
        assertEquals(50, preview.targets.single().asked)
        assertEquals(0, preview.targets.single().correct)
    }

    @Test
    fun compositionRoundTripsThroughJson() {
        val composition = Composition.of(
            FillSource.WEAKEST_TOPICS to 12,
            FillSource.RANDOM to 8
        )
        val parsed = Composition.fromJson(composition.toJson())
        assertEquals(composition, parsed)
        assertNull(Composition.fromJson(""))
        assertNull(Composition.fromJson("not json"))
    }

    @Test
    fun theSameSeedProducesTheSameSelection() {
        val signals = (1..20).map { signal("q$it", "c${it % 3}", correct = it % 2) }
        val first = TestSelector.select(plan(TestType.WEAKEST_TOPICS, 10, seed = 11L), signals)
        val second = TestSelector.select(plan(TestType.WEAKEST_TOPICS, 10, seed = 11L), signals)
        assertEquals(first, second)
    }

    @Test
    fun anEmptyPoolReturnsAnEmptySelection() {
        val selection: Selection = TestSelector.select(
            plan(TestType.WEAKEST_TOPICS, 20),
            emptyList()
        )!!
        assertTrue(selection.ids.isEmpty())
        assertTrue(selection.targets.isEmpty())
    }

    @Test
    fun categoriesKeepTheirSupplyCount() {
        val signals = (1..4).map { signal("q$it", "c") }
        val categories = TestRanking.categories(signals)
        assertEquals(1, categories.size)
        assertEquals(4, categories.single().questionCount)
    }
}
