package com.mcqapp

import com.mcqapp.domain.ExclusionMode
import com.mcqapp.domain.ScopeKey
import com.mcqapp.domain.TestPlan
import com.mcqapp.domain.TestPlan.Resolution
import com.mcqapp.domain.TestSetup
import com.mcqapp.domain.TestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup sheet's length, clamp and validation rules (sections 7.3-7.5).
 * Pure JVM: no database, no clock, so every branch of [TestPlan.resolve] is
 * pinned here rather than discovered from a running session.
 */
class TestPlanTest {

    private fun plan(
        categoryIds: List<String> = emptyList(),
        type: TestType? = TestType.RANDOM,
        count: Int = 20,
        minutes: Int = 0,
        samplingVersion: Int = TestPlan.SAMPLING_VERSION,
        scopeKey: String = ScopeKey.wholePaper("p1"),
        exclusionMode: ExclusionMode = ExclusionMode.NONE,
        targetTopicIds: List<String> = emptyList(),
        variant: String? = null
    ) = TestPlan(
        paperId = "p1",
        categoryIds = categoryIds,
        type = type,
        count = count,
        minutes = minutes,
        samplingVersion = samplingVersion,
        scopeKey = scopeKey,
        exclusionMode = exclusionMode,
        targetTopicIds = targetTopicIds,
        variant = variant
    )

    @Test
    fun allowedLengthsAreEveryTenFrom20To150() {
        assertEquals((20..150 step 10).toList(), TestPlan.LENGTHS)
        assertTrue(TestPlan.isAllowedLength(20))
        assertTrue(TestPlan.isAllowedLength(150))
        assertFalse(TestPlan.isAllowedLength(10))
        assertFalse(TestPlan.isAllowedLength(160))
        assertFalse(TestPlan.isAllowedLength(25))
        assertFalse(TestPlan.isAllowedLength(0))
    }

    @Test
    fun thePresetsAreTheSixChips() {
        assertEquals(listOf(20, 50, 80, 100, 120, 150), TestPlan.PRESETS)
        assertTrue(TestPlan.PRESETS.all { TestPlan.isAllowedLength(it) })
        assertEquals(20, TestPlan.DEFAULT_LENGTH)
    }

    @Test
    fun maxAllowedAtTheBoundaries() {
        assertEquals(0, plan().maxAllowed(0))
        assertEquals(0, plan().maxAllowed(-5))
        assertEquals(19, plan().maxAllowed(19))
        assertEquals(20, plan().maxAllowed(20))
        assertEquals(20, plan().maxAllowed(29))
        assertEquals(130, plan().maxAllowed(137))
        assertEquals(150, plan().maxAllowed(150))
        assertEquals(150, plan().maxAllowed(200))
        assertEquals(150, plan().maxAllowed(Int.MAX_VALUE))
    }

    @Test
    fun anEmptyPoolRefusesWithTheScopeMessage() {
        val refused = plan().resolve(0) as Resolution.Refused
        assertEquals(TestPlan.EMPTY_SCOPE_MESSAGE, refused.message)
        assertFalse(plan().isValid(0))
    }

    @Test
    fun aShortScopeRunsTheWholeScope() {
        val ready = plan(count = 20).resolve(12) as Resolution.Ready
        assertEquals(12, ready.count)
        assertTrue(ready.short)
        assertTrue(ready.clamped)
        assertEquals("Only 12 questions in this scope.", ready.message)
        assertTrue(plan(count = 20).isValid(12))
    }

    @Test
    fun aRequestAboveThePoolClampsAndSaysSo() {
        val ready = plan(count = 30).resolve(25) as Resolution.Ready
        assertEquals(20, ready.count)
        assertFalse(ready.short)
        assertTrue(ready.clamped)
        assertEquals("Only 25 questions in this scope.", ready.message)

        val large = plan(count = 150).resolve(137) as Resolution.Ready
        assertEquals(130, large.count)
        assertTrue(large.clamped)

        val huge = plan(count = 150).resolve(200) as Resolution.Ready
        assertEquals(150, huge.count)
        assertFalse(huge.clamped)
    }

    @Test
    fun aRequestWithinThePoolIsUntouched() {
        val ready = plan(count = 20).resolve(25) as Resolution.Ready
        assertEquals(20, ready.count)
        assertFalse(ready.short)
        assertFalse(ready.clamped)
        assertNull(ready.message)

        val exact = plan(count = 130).resolve(137) as Resolution.Ready
        assertEquals(130, exact.count)
        assertFalse(exact.clamped)
    }

    @Test
    fun aClampNeverExceeds150() {
        for (available in listOf(200, 1_000, Int.MAX_VALUE)) {
            val ready = plan(count = 150).resolve(available) as Resolution.Ready
            assertTrue("available=$available", ready.count <= TestPlan.MAX_LENGTH)
            assertTrue("available=$available", plan().maxAllowed(available) <= TestPlan.MAX_LENGTH)
        }
    }

    @Test
    fun invalidRequestedLengthsAreRefused() {
        for (count in listOf(0, 10, 25, 160)) {
            val refused = plan(count = count).resolve(1_000) as Resolution.Refused
            assertEquals("count=$count", TestPlan.LENGTH_MESSAGE, refused.message)
            assertFalse("count=$count", plan(count = count).isValid(1_000))
        }
    }

    @Test
    fun aPlanWithoutATypeIsNotResolvedAsRandom() {
        val nullType = plan(type = null, count = 0).resolve(1_000) as Resolution.Refused
        assertEquals(TestPlan.NO_TYPE_MESSAGE, nullType.message)

        val nullTypeWithCount = plan(type = null, count = 20).resolve(1_000) as Resolution.Refused
        assertEquals(TestPlan.NO_TYPE_MESSAGE, nullTypeWithCount.message)
    }

    @Test
    fun aVersionOnePlanIsRefusedAsOld() {
        val oldVersion = plan(samplingVersion = 1).resolve(1_000) as Resolution.Refused
        assertEquals(TestPlan.OLD_VERSION_MESSAGE, oldVersion.message)
    }

    @Test
    fun aWholePaperPlanMustUseTheWholePaperKey() {
        // Empty categoryIds means the whole paper (TestViewModel's convention),
        // so the empty-scope key would put the attempt in the wrong class.
        val wrong = plan(scopeKey = ScopeKey.ofExpanded(emptyList()))
            .resolve(100) as Resolution.Refused
        assertEquals(TestPlan.WHOLE_PAPER_KEY_MESSAGE, wrong.message)
        assertTrue(plan().isValid(100))
    }

    @Test
    fun aScopedPlanCannotUseTheWholePaperKey() {
        val wrong = plan(categoryIds = listOf("c1")).resolve(100) as Resolution.Refused
        assertEquals(TestPlan.SCOPED_KEY_MESSAGE, wrong.message)
        assertTrue(
            plan(categoryIds = listOf("c1"), scopeKey = ScopeKey.ofExpanded(listOf("c1")))
                .isValid(100)
        )
    }

    @Test
    fun freshSamplingIsOnlyAllowedForItsTypes() {
        assertTrue(plan(exclusionMode = ExclusionMode.RECENT_3).isValid(100))
        assertTrue(
            plan(type = TestType.EASIEST_QUESTIONS, exclusionMode = ExclusionMode.RECENT_3)
                .isValid(100)
        )
        val refused = plan(type = TestType.MISTAKES, exclusionMode = ExclusionMode.RECENT_3)
            .resolve(100) as Resolution.Refused
        assertEquals(TestPlan.FRESH_MESSAGE, refused.message)
    }

    @Test
    fun targetsAreOnlyAllowedForTheirTypes() {
        assertTrue(
            plan(type = TestType.WEAKEST_TOPICS, targetTopicIds = listOf("c1", "c2"))
                .isValid(100)
        )
        assertTrue(plan(type = TestType.WEAKEST_TOPICS).isValid(100))

        val wrongType = plan(type = TestType.RANDOM, targetTopicIds = listOf("c1"))
            .resolve(100) as Resolution.Refused
        assertEquals(TestPlan.TARGET_MESSAGE, wrongType.message)

        val unsorted = plan(
            type = TestType.WEAKEST_TOPICS,
            targetTopicIds = listOf("c2", "c1", "c1")
        ).resolve(100) as Resolution.Refused
        assertEquals(TestPlan.TARGET_ORDER_MESSAGE, unsorted.message)
    }

    @Test
    fun aMissingScopeKeyIsInvalid() {
        val blank = plan(scopeKey = "  ").resolve(100) as Resolution.Refused
        assertEquals(TestPlan.SCOPE_MESSAGE, blank.message)
    }

    @Test
    fun suggestedMinutesFollowsTheDefaultPace() {
        assertEquals(20, plan(count = 20).suggestedMinutes())
        assertEquals(50, plan(count = 50).suggestedMinutes())
        assertEquals(150, plan(count = 150).suggestedMinutes())
        assertEquals(25, plan(count = 25).suggestedMinutes())
    }

    @Test
    fun suggestedMinutesRoundsUpAndNeverDropsBelowOne() {
        assertEquals(1, plan(count = 1).suggestedMinutes())
        assertEquals(1, plan(count = 0).suggestedMinutes())
        // 20 questions at 20 s = 400 s = 6.67 min -> 7.
        assertEquals(7, plan(count = 20).suggestedMinutes(secondsPerQuestion = 20))
        assertEquals(30, plan(count = 20).suggestedMinutes(secondsPerQuestion = 90))
    }

    @Test
    fun thePlanKeepsTheRequestedCountWhenItClamps() {
        val requested = plan(count = 30)
        val ready = requested.resolve(25) as Resolution.Ready
        assertEquals(20, ready.count)
        assertEquals(30, requested.count)
    }

    @Test
    fun zeroMinutesMeansUntimedAndAChosenValueIsKept() {
        assertEquals(0, plan().minutes)
        assertEquals(25, plan(minutes = 25).minutes)
    }

    @Test
    fun onlyRandomAndBalancedAreRepresentative() {
        assertTrue(TestType.RANDOM.isRepresentative)
        assertTrue(TestType.BALANCED.isRepresentative)
        assertFalse(TestType.EASIEST_QUESTIONS.isRepresentative)
        assertFalse(TestType.MISTAKES.isRepresentative)
        assertTrue(TestType.MISTAKES.isPractice)
    }

    @Test
    fun coverageIsPracticeAndUsesTheSignalSelector() {
        // Plan §10: equal allocation over-represents small topics, so Coverage
        // must never feed the mock trend or the `rep*` counters.
        assertFalse(TestType.COVERAGE.isRepresentative)
        assertTrue(TestType.COVERAGE.isPractice)
        assertTrue(TestType.COVERAGE.usesSignalSelector)
        assertFalse(TestType.COVERAGE.supportsFreshSample)
        assertTrue(TestSetup.isStartableType(TestType.COVERAGE))
        val coverage = plan(type = TestType.COVERAGE)
        assertNull(coverage.validationError(100))
        assertEquals(
            Resolution.Ready(count = 20, short = false, clamped = false, message = null),
            coverage.resolve(100)
        )
    }

    @Test
    fun coverageOnlyAcceptsTheExposureMode() {
        assertNull(plan(type = TestType.COVERAGE).validationError(100))
        assertNull(
            plan(type = TestType.COVERAGE, variant = TestPlan.EXPOSURE_VARIANT)
                .validationError(100)
        )
        assertEquals(
            TestPlan.COVERAGE_VARIANT_MESSAGE,
            plan(type = TestType.COVERAGE, variant = "OTHER").validationError(100)
        )
    }

    @Test
    fun aStoredTypeIsNullWhenUnknownOrMissing() {
        assertEquals(TestType.RANDOM, TestType.fromStorage("RANDOM"))
        assertEquals(TestType.MIXED, TestType.fromStorage(" MIXED "))
        assertNull(TestType.fromStorage(null))
        assertNull(TestType.fromStorage(""))
        assertNull(TestType.fromStorage("UNKNOWN"))
    }

    @Test
    fun theSamplerLimitMatchesTheBoundedSampler() {
        // min(150, target + max(1, ceil(target/4))), the section 9.6 limit.
        assertEquals(25, TestPlan.samplerLimit(20))
        assertEquals(32, TestPlan.samplerLimit(25))
        assertEquals(63, TestPlan.samplerLimit(50))
        assertEquals(150, TestPlan.samplerLimit(150))
        assertEquals(150, TestPlan.samplerLimit(149))
        assertTrue(TestPlan.samplerCanStart(20, 25))
        assertFalse(TestPlan.samplerCanStart(20, 26))
        assertTrue(TestPlan.samplerCanStart(20, 1))
    }

    @Test
    fun aFreshSampleNeedsTwiceTheRequestLeft() {
        assertFalse(TestPlan.freshSampleFits(remaining = 39, requested = 20))
        assertTrue(TestPlan.freshSampleFits(remaining = 40, requested = 20))
        assertTrue(TestPlan.freshSampleFits(remaining = 100, requested = 20))
        assertFalse(TestPlan.freshSampleFits(remaining = 100, requested = 0))
    }

    @Test
    fun theSamplerMessagesNameTheWayOut() {
        assertTrue(TestPlan.noPassageFitsMessage().contains("longer test"))
        assertTrue(TestPlan.passageTooLargeMessage().contains("150"))
    }

    @Test
    fun aRestoredLengthSnapsIntoTheAllowedRange() {
        // Plan §10: a stored length may predate the current step, or be
        // corrupt; it can never reach the plan unchanged.
        assertEquals(20, TestPlan.normalizeLength(Int.MIN_VALUE))
        assertEquals(20, TestPlan.normalizeLength(-40))
        assertEquals(20, TestPlan.normalizeLength(0))
        assertEquals(20, TestPlan.normalizeLength(20))
        assertEquals(20, TestPlan.normalizeLength(24))
        assertEquals(30, TestPlan.normalizeLength(25))
        assertEquals(30, TestPlan.normalizeLength(26))
        assertEquals(70, TestPlan.normalizeLength(70))
        assertEquals(150, TestPlan.normalizeLength(145))
        assertEquals(150, TestPlan.normalizeLength(149))
        assertEquals(150, TestPlan.normalizeLength(150))
        assertEquals(150, TestPlan.normalizeLength(Int.MAX_VALUE))
        for (value in listOf(-1, 0, 21, 34, 77, 148, 999)) {
            assertTrue(TestPlan.isAllowedLength(TestPlan.normalizeLength(value)))
        }
    }
}
