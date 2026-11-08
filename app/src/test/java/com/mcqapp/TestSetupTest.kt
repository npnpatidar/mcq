package com.mcqapp

import com.mcqapp.domain.ExamTiming
import com.mcqapp.domain.ExclusionMode
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
        categoryIds: List<String> = emptyList(),
        exclusionMode: ExclusionMode = ExclusionMode.NONE,
        minBlockSize: Int = 1,
        variant: String? = null
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
        available = available,
        exclusionMode = exclusionMode,
        variant = variant,
        minBlockSize = minBlockSize
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
    fun phaseThreeShipsAllCustomTypesWithRandomAndMistakes() {
        assertEquals(
            listOf(
                TestType.RANDOM,
                TestType.BALANCED,
                TestType.MISTAKES,
                TestType.WEAKEST_TOPICS,
                TestType.TOUGHEST_QUESTIONS,
                TestType.EASIEST_QUESTIONS,
                TestType.TRICKY,
                TestType.AT_RISK,
                TestType.UNSEEN,
                TestType.MIXED,
                TestType.BOOKMARKED,
                TestType.TAG,
                TestType.AUTHORED_DIFFICULTY,
                TestType.SLOWEST
            ),
            TestSetup.STARTABLE_TYPES
        )
        TestType.entries.forEach { assertTrue(TestSetup.isStartableType(it)) }
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
        assertEquals(ExclusionMode.NONE, plan.exclusionMode)
    }

    @Test
    fun theSamplerPreCheckRefusesWhenTheSmallestBlockCannotFit() {
        // 20 questions allow a 25-question overshoot; a 26-question passage
        // cannot start, so Start must not emit a route that fails at load.
        val tooLarge = setup(available = 1_000, requested = 20, minBlockSize = 26)
        assertFalse(tooLarge.startable())
        assertEquals(TestPlan.noPassageFitsMessage(), tooLarge.message())
        assertEquals(0, tooLarge.resolvedCount())

        val fits = setup(available = 1_000, requested = 20, minBlockSize = 25)
        assertTrue(fits.startable())
        assertEquals(TestPlan.DEFAULT_LENGTH, fits.resolvedCount())
    }

    @Test
    fun aBlockLargerThanTheHardCapNamesTheWayOut() {
        val impossible = setup(available = 1_000, requested = 20, minBlockSize = 151)
        assertFalse(impossible.startable())
        assertEquals(TestPlan.passageTooLargeMessage(), impossible.message())
    }

    @Test
    fun aFreshSampleIsItsOwnComparability() {
        val fresh = setup(
            type = TestType.RANDOM,
            available = 1_000,
            exclusionMode = ExclusionMode.RECENT_3
        )
        assertEquals(TestComparability.FRESH, fresh.comparability())
        assertEquals(ExclusionMode.RECENT_3, fresh.plan().exclusionMode)

        // A short scope is short before it is fresh: the label describes the
        // length that actually ran.
        val shortFresh = setup(
            type = TestType.RANDOM,
            requested = 20,
            available = 12,
            exclusionMode = ExclusionMode.RECENT_3
        )
        assertEquals(TestComparability.SHORT, shortFresh.comparability())
    }



    @Test
    fun aTagSetupWithoutATagCannotStart() {
        val noVariant = setup(type = TestType.TAG, available = 100)
        assertFalse(noVariant.startable())
        assertEquals(TestPlan.VARIANT_REQUIRED_MESSAGE, noVariant.message())
    }

    @Test
    fun aTagSetupWithATagStarts() {
        val good = setup(type = TestType.TAG, available = 100, variant = "polity")
        assertTrue(good.startable())
        assertEquals("polity", good.plan().variant)
    }

    @Test
    fun anAuthoredSetupWithoutADifficultyCannotStart() {
        val noVariant = setup(type = TestType.AUTHORED_DIFFICULTY, available = 100)
        assertFalse(noVariant.startable())
        assertEquals(TestPlan.VARIANT_REQUIRED_MESSAGE, noVariant.message())
    }

    @Test
    fun anAuthoredSetupWithAnUnknownDifficultyCannotStart() {
        val bad = setup(type = TestType.AUTHORED_DIFFICULTY, available = 100, variant = "extreme")
        assertFalse(bad.startable())
        assertEquals(TestPlan.VARIANT_REQUIRED_MESSAGE, bad.message())
    }

    @Test
    fun anAuthoredSetupWithADifficultyStarts() {
        val good = setup(type = TestType.AUTHORED_DIFFICULTY, available = 100, variant = "HARD")
        assertTrue(good.startable())
        assertEquals("HARD", good.plan().variant)
    }

    @Test
    fun bookmarkedAndSlowestNeedNoVariant() {
        assertTrue(setup(type = TestType.BOOKMARKED, available = 100).startable())
        assertTrue(setup(type = TestType.SLOWEST, available = 100).startable())
    }

    @Test
    fun aNonCustomSetupIgnoresAnUnsetVariant() {
        val random = setup(type = TestType.RANDOM, available = 1000)
        assertNull(random.plan().variant)
    }
}
