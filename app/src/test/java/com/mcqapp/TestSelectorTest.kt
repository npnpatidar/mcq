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
        dueAt: Long = 0L
    ): Signals = QuestionSignals.of(
        QuestionSignalInput(
            questionId = id,
            categoryId = category,
            categoryTitle = title,
            authoredDifficulty = authored,
            correct = correct,
            wrong = wrong,
            card = CardSignal(
                difficulty = 5.0,
                stability = 10.0,
                reviewCount = 5,
                lapses = 0,
                leech = leech,
                dueAt = dueAt,
                lastReviewedAt = 1L,
                contentHashMatches = true,
                state = CardStateKind.REVIEW
            ),
            retention = 0.9
        ),
        now = 1_000L
    )

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
