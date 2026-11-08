package com.mcqapp

import com.mcqapp.domain.ExamTiming
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestComparability
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestSetup
import com.mcqapp.domain.TestTime
import com.mcqapp.domain.TestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup sheet's pure rules (section 7.2–7.5). The sheet must never emit a
 * route the session refuses, so every preview, clamp and Start decision is
 * tested without Compose or a database.
 */
class TestSetupTest {

    private fun setup(
        type: TestType = TestType.RANDOM,
        requested: Int = TestPlan.DEFAULT_LENGTH,
        available: Int,
        time: TestTime = TestTime.Suggested,
        categoryIds: List<String> = emptyList()
    ) = TestSetup(
        paperId = "p1",
        categoryIds = categoryIds,
        scopeKey = if (categoryIds.isEmpty()) {
            ScopeKey.wholePaper("p1")
        } else {
            ScopeKey.ofExpanded(categoryIds)
        },
        type = type,
        requestedCount = requested,
        time = time,
        available = available
    )

    @Test
    fun anEmptyPoolCannotStartAndSaysWhy() {
        val empty = setup(available = 0)
        assertFalse(empty.startable())
        assertEquals(TestPlan.EMPTY_SCOPE_MESSAGE, empty.message())
        assertEquals(0, empty.resolvedCount())
    }

    @Test
    fun anEmptyMistakePoolIsNotStartableEvenThoughThePaperHasQuestions() {
        val empty = setup(type = TestType.MISTAKES, available = 0)
        assertFalse(empty.startable())
        assertEquals(TestPlan.EMPTY_SCOPE_MESSAGE, empty.message())
    }

    @Test
    fun aScopeBelowTwentyRunsWholeAndIsLabelledShort() {
        val short = setup(requested = 20, available = 12)
        assertTrue(short.startable())
        assertTrue(short.short())
        assertEquals(12, short.resolvedCount())
        assertEquals(TestComparability.SHORT, short.comparability())
        // The clock follows the resolved length, not the impossible request.
        assertEquals(ExamTiming.suggestedMinutes(12), short.suggestedMinutes())
    }

    @Test
    fun aScopeOfTwentyToTwentyNineCanOnlyRunTwenty() {
        val pool25 = setup(requested = 20, available = 25)
        assertTrue(pool25.startable())
        assertFalse(pool25.short())
        assertEquals(20, pool25.resolvedCount())
        assertEquals(20, TestPlan.maxAllowedFor(25))
    }

    @Test
    fun aRequestAboveThePoolClampsAndSaysOnlyN() {
        val clamped = setup(requested = 50, available = 25)
        assertTrue(clamped.startable())
        assertTrue(clamped.clamped())
        assertEquals(20, clamped.resolvedCount())
        assertEquals("Only 25 questions in this scope.", clamped.message())
        // The preview shows the length actually sat.
        assertEquals(TestComparability.COMPARABLE, clamped.comparability())
    }

    @Test
    fun aRequestWithinThePoolIsUntouched() {
        val exact = setup(requested = 50, available = 137)
        assertEquals(50, exact.resolvedCount())
        assertFalse(exact.clamped())
        assertNull(exact.message())
    }

    @Test
    fun thePresetsAreTheSixChips() {
        assertEquals(listOf(20, 50, 80, 100, 120, 150), TestPlan.PRESETS)
    }

    @Test
    fun theDefaultLengthIsTwenty() {
        assertEquals(20, TestPlan.DEFAULT_LENGTH)
        assertEquals(20, setup(available = 1_000).resolvedCount())
    }

    @Test
    fun theSuggestedTimeFollowsTheResolvedLength() {
        assertEquals(20, setup(requested = 20, available = 1_000).minutes())
        assertEquals(50, setup(requested = 50, available = 1_000).minutes())
        assertEquals(150, setup(requested = 150, available = 1_000).minutes())
        // A clamp changes the clock too.
        assertEquals(20, setup(requested = 50, available = 25).minutes())
    }

    @Test
    fun anOverrideWinsAndZeroMeansUntimed() {
        val override = setup(available = 1_000, time = TestTime.Fixed(7))
        assertEquals(7, override.minutes())
        val untimed = setup(available = 1_000, time = TestTime.Fixed(0))
        assertEquals(0, untimed.minutes())
    }

    @Test
    fun randomIsComparableAndMistakesIsPractice() {
        assertEquals(
            TestComparability.COMPARABLE,
            setup(type = TestType.RANDOM, available = 100).comparability()
        )
        assertEquals(
            TestComparability.PRACTICE,
            setup(type = TestType.MISTAKES, available = 100).comparability()
        )
    }

    @Test
    fun phaseOneShipsOnlyRandomAndMistakes() {
        assertEquals(
            listOf(TestType.RANDOM, TestType.MISTAKES),
            TestSetup.STARTABLE_TYPES
        )
        assertTrue(TestSetup.isStartableType(TestType.RANDOM))
        assertTrue(TestSetup.isStartableType(TestType.MISTAKES))
        assertFalse(TestSetup.isStartableType(TestType.BALANCED))
        assertFalse(TestSetup.isStartableType(TestType.WEAKEST_TOPICS))
    }

    @Test
    fun theRouteCarriesTheRequestedLengthAndTheBoundedVersion() {
        val plan = setup(
            type = TestType.MISTAKES,
            requested = 50,
            available = 100,
            categoryIds = listOf("c1")
        ).plan()
        assertEquals(TestType.MISTAKES, plan.type)
        assertEquals("the route keeps what was asked, not the clamp", 50, plan.count)
        assertEquals(TestPlan.SAMPLING_VERSION, plan.samplingVersion)
        assertEquals(ScopeKey.ofExpanded(listOf("c1")), plan.scopeKey)
        assertEquals(50, plan.minutes)
    }
}
