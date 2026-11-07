package com.mcqapp.domain

/**
 * Whether a test avoids questions asked in the last three comparable attempts
 * of the same paper (section 9.8). [RECENT_3] makes the attempt a **Fresh
 * sample**, which is comparable only to another fresh sample under the same
 * rule.
 */
enum class ExclusionMode { NONE, RECENT_3 }

/**
 * A bounded Test as the setup sheet and the route describe it: which paper and
 * categories, which selector, how many questions, how long, and the metadata
 * an attempt stores so it can be compared and resumed (section 13, phase 1).
 *
 * The plan holds the **requested** length, not the shown one. A passage block
 * may push the shown count above it (never above [MAX_LENGTH]), and a scope
 * that cannot supply the request resolves to a smaller, labelled session via
 * [resolve]. Nothing here reads a database or a clock, so every rule is
 * JVM-testable.
 *
 * [type] is nullable because a pre-v21 attempt has no type; a legacy plan is
 * never resolved as RANDOM (section 16 C5).
 */
data class TestPlan(
    val paperId: String,
    val categoryIds: List<String> = emptyList(),
    val type: TestType?,
    /** The requested length; 20..150 step 10 for a real test. */
    val count: Int,
    /** Chosen duration in minutes; 0 means untimed. */
    val minutes: Int = 0,
    val seed: Long = 0L,
    val samplingVersion: Int = SAMPLING_VERSION,
    /** Canonical scope identity from [ScopeKey]. */
    val scopeKey: String,
    val exclusionMode: ExclusionMode = ExclusionMode.NONE,
    /** Sorted, deduped targets; empty unless [TestType.supportsTargets]. */
    val targetTopicIds: List<String> = emptyList()
) {

    /**
     * The largest selectable length that fits [available] (section 7.3): the
     * whole scope when it is short, otherwise the largest multiple of ten up
     * to 150.
     */
    fun maxAllowed(available: Int): Int = maxAllowedFor(available)

    /** Whether the plan can start; a clampable request is still valid. */
    fun isValid(available: Int): Boolean = validationError(available) == null

    /**
     * Why the plan cannot start, or null when it can. A request larger than
     * the pool is valid because [resolve] clamps it and reports that it did.
     */
    fun validationError(available: Int): String? {
        if (type == null) return NO_TYPE_MESSAGE
        if (samplingVersion < SAMPLING_VERSION) return OLD_VERSION_MESSAGE
        if (!isAllowedLength(count)) return LENGTH_MESSAGE
        if (scopeKey.isBlank()) return SCOPE_MESSAGE
        if (categoryIds.isEmpty() && scopeKey != ScopeKey.wholePaper(paperId)) {
            return WHOLE_PAPER_KEY_MESSAGE
        }
        if (categoryIds.isNotEmpty() && scopeKey == ScopeKey.wholePaper(paperId)) {
            return SCOPED_KEY_MESSAGE
        }
        if (exclusionMode != ExclusionMode.NONE && type?.supportsFreshSample != true) {
            return FRESH_MESSAGE
        }
        if (targetTopicIds != canonicalTargets(targetTopicIds)) return TARGET_ORDER_MESSAGE
        if (targetTopicIds.isNotEmpty() && type?.supportsTargets != true) return TARGET_MESSAGE
        if (available <= 0) return EMPTY_SCOPE_MESSAGE
        return null
    }

    /**
     * The session this plan starts with [available] questions in the pool.
     *
     * Refusal is a value, not an empty session: `available == 0` has no
     * questions, and a legacy or malformed plan cannot start. A request above
     * the pool is clamped to [maxAllowed] and says so, so Start never silently
     * runs a smaller test.
     */
    fun resolve(available: Int): Resolution {
        validationError(available)?.let { return Resolution.Refused(it) }
        if (available < MIN_LENGTH) {
            return Resolution.Ready(
                count = available,
                short = true,
                clamped = count > available,
                message = if (count > available) onlyNMessage(available) else null
            )
        }
        if (count > available) {
            return Resolution.Ready(
                count = maxAllowedFor(available),
                short = false,
                clamped = true,
                message = onlyNMessage(available)
            )
        }
        return Resolution.Ready(count = count, short = false, clamped = false, message = null)
    }

    /** The default pace's suggested duration for the requested count. */
    fun suggestedMinutes(
        secondsPerQuestion: Int = ExamTiming.DEFAULT_SECONDS_PER_QUESTION
    ): Int = ExamTiming.suggestedMinutes(count, secondsPerQuestion)

    /** The session this plan resolves to, or the reason it cannot start. */
    sealed interface Resolution {

        data class Ready(
            /** The count to run: the request, the clamped length or the short scope. */
            val count: Int,
            /** The whole scope is below 20 questions. */
            val short: Boolean,
            /** The request was larger than the pool and was reduced. */
            val clamped: Boolean,
            /** What to tell the learner about the clamp, or null. */
            val message: String?
        ) : Resolution

        data class Refused(val message: String) : Resolution
    }

    companion object {

        const val MIN_LENGTH: Int = 20
        const val MAX_LENGTH: Int = 150
        const val STEP: Int = 10
        const val DEFAULT_LENGTH: Int = 20

        /** New attempts write 2; 1 reads as legacy (section 16 C5). */
        const val SAMPLING_VERSION: Int = 2

        /** Every selectable requested length. */
        val LENGTHS: List<Int> = (MIN_LENGTH..MAX_LENGTH step STEP).toList()

        /** The sheet's length chips. */
        val PRESETS: List<Int> = listOf(20, 50, 80, 100, 120, 150)

        const val EMPTY_SCOPE_MESSAGE: String = "This scope has no questions."
        const val NO_TYPE_MESSAGE: String = "This test has no type; start a new test."
        const val OLD_VERSION_MESSAGE: String =
            "This test was saved by an older version; start a new test."
        const val LENGTH_MESSAGE: String = "Choose a length between 20 and 150 in steps of 10."
        const val SCOPE_MESSAGE: String = "The test scope is missing."
        const val WHOLE_PAPER_KEY_MESSAGE: String =
            "A whole-paper test must use the whole-paper scope key."
        const val SCOPED_KEY_MESSAGE: String =
            "A scoped test cannot use the whole-paper scope key."
        const val FRESH_MESSAGE: String = "This test type cannot avoid recently tested questions."
        const val TARGET_MESSAGE: String = "This test type cannot record target topics."
        const val TARGET_ORDER_MESSAGE: String = "Target topics must be sorted and deduplicated."

        /** The pure form of [maxAllowed]; callable without a plan. */
        fun maxAllowedFor(available: Int): Int {
            if (available <= 0) return 0
            if (available < MIN_LENGTH) return available
            val capped = minOf(available, MAX_LENGTH)
            return capped - capped % STEP
        }

        fun isAllowedLength(count: Int): Boolean =
            count in MIN_LENGTH..MAX_LENGTH && count % STEP == 0

        /** The stored form of a target set: sorted and deduplicated. */
        fun canonicalTargets(ids: Collection<String>): List<String> = ids.distinct().sorted()

        private fun onlyNMessage(available: Int): String =
            "Only $available questions in this scope."
    }
}
