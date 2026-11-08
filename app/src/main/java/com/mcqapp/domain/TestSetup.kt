package com.mcqapp.domain

/**
 * The time a setup-sheet run is given: the 1-minute-per-question default, or
 * an explicit choice where 0 means untimed (section 7.4).
 *
 * [Suggested] follows the *resolved* length, so a short scope or a clamped
 * request gets the clock its questions deserve rather than the clock of the
 * request that could not be met.
 */
sealed interface TestTime {
    /** 1 minute per question of the resolved length. */
    data object Suggested : TestTime

    /** An explicit number of minutes; 0 means untimed. */
    data class Fixed(val minutes: Int) : TestTime
}

/**
 * How an attempt's numbers may be read (section 11.5).
 *
 * A practice test is never plotted against a mock, and a short test is
 * labelled with its real length rather than with the comparability of the
 * type it would have been.
 */
enum class TestComparability {
    /** A representative mock: comparable to another test of the same design. */
    COMPARABLE,

    /** Practice: not comparable to a mock. */
    PRACTICE,

    /** The whole scope is below the minimum length. */
    SHORT
}

/**
 * The phase-1 setup sheet as pure values: the selected type, the requested
 * length, the time and the pool they resolve against.
 *
 * The sheet must never emit a route that the session's plan builder refuses
 * (section 16 C5), so the same [TestPlan] rules decide the preview, the clamp
 * and the Start button. Nothing here reads a database or a clock, so every
 * branch is JVM-testable.
 *
 * [categoryIds] are the **ticked** ids, as the route carries them, and
 * [scopeKey] is the canonical key of their expansion (or the whole-paper key
 * when nothing is ticked). [available] is the pool the selected type draws
 * from: the scope's question count for Random, its mistake count for Mistakes.
 */
data class TestSetup(
    val paperId: String,
    val categoryIds: List<String>,
    val scopeKey: String,
    val type: TestType,
    /** The length the learner asked for; 20..150 step 10. */
    val requestedCount: Int = TestPlan.DEFAULT_LENGTH,
    val time: TestTime = TestTime.Suggested,
    /** The pool the selected type draws from. */
    val available: Int
) {

    /**
     * The session this setup starts, or the reason it cannot start.
     *
     * The minutes do not matter for the resolution, so it is computed from a
     * plan with a placeholder to keep the two paths apart.
     */
    fun resolution(): TestPlan.Resolution = plan(minutes = 0).resolve(available)

    /** The plan the route carries. The session draws the sampling seed. */
    fun plan(): TestPlan = plan(minutes = minutes())

    private fun plan(minutes: Int): TestPlan = TestPlan(
        paperId = paperId,
        categoryIds = categoryIds,
        type = type,
        count = requestedCount,
        minutes = minutes,
        samplingVersion = TestPlan.SAMPLING_VERSION,
        scopeKey = scopeKey
    )

    /** The length the session actually runs: the request, the clamp or the short scope. */
    fun resolvedCount(): Int =
        (resolution() as? TestPlan.Resolution.Ready)?.count ?: 0

    /** Whether the whole scope is below the minimum length. */
    fun short(): Boolean = (resolution() as? TestPlan.Resolution.Ready)?.short == true

    /** Whether the request was larger than the pool and was reduced. */
    fun clamped(): Boolean = (resolution() as? TestPlan.Resolution.Ready)?.clamped == true

    /** What to tell the learner: the refusal, the clamp note, or nothing. */
    fun message(): String? = when (val resolution = resolution()) {
        is TestPlan.Resolution.Refused -> resolution.message
        is TestPlan.Resolution.Ready -> resolution.message
    }

    /** Whether Start may be pressed: the plan resolves and the pool is not empty. */
    fun startable(): Boolean = resolution() is TestPlan.Resolution.Ready

    /** The clock the default pace suggests for the resolved length. */
    fun suggestedMinutes(): Int = ExamTiming.suggestedMinutes(resolvedCount())

    /** The minutes this run gets; 0 means untimed. */
    fun minutes(): Int = when (time) {
        TestTime.Suggested -> suggestedMinutes()
        is TestTime.Fixed -> time.minutes.coerceAtLeast(0)
    }

    /** How the attempt's numbers may be read. */
    fun comparability(): TestComparability = when {
        short() -> TestComparability.SHORT
        type.isRepresentative -> TestComparability.COMPARABLE
        else -> TestComparability.PRACTICE
    }

    companion object {

        /**
         * The selectors the phase-1 sheet offers (section 13). The others
         * exist in the model but have no selector yet, so offering them would
         * produce a route the session refuses (section 16 C5).
         */
        val STARTABLE_TYPES: List<TestType> = listOf(TestType.RANDOM, TestType.MISTAKES)

        /** Whether [type] can be started in phase 1. */
        fun isStartableType(type: TestType): Boolean = type in STARTABLE_TYPES
    }
}
