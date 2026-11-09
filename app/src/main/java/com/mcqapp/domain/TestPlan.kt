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
    val targetTopicIds: List<String> = emptyList(),
    /** The recorded fill composition (stats.md §9.7); empty on older plans. */
    val compositionJson: String = "",
    /**
     * The custom type's choice (phase 3): the tag or difficulty name a Tag /
     * Authored difficulty attempt covers, or Coverage's exposure mode
     * ([EXPOSURE_VARIANT]). Null for every other type.
     */
    val variant: String? = null
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
        // A fixed panel runs its whole saved set, so its length is the panel's
        // size, not one of the sampled steps (a deleted question can make it
        // 47). Every sampled type keeps the 20..150 step-of-ten rule.
        if (type == TestType.FIXED_PANEL) {
            if (count !in 1..MAX_LENGTH) return LENGTH_MESSAGE
        } else if (!isAllowedLength(count)) {
            return LENGTH_MESSAGE
        }
        // A panel is a whole-paper frozen set. Running it under a category
        // scope would drop members, so two runs of "the same" panel would not
        // be the same set.
        if (type == TestType.FIXED_PANEL && categoryIds.isNotEmpty()) {
            return PANEL_SCOPE_MESSAGE
        }
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
        // A custom type without its choice cannot start: a Tag run needs the
        // tag, an Authored difficulty run needs the label.
        if (type == TestType.TAG && variant.isNullOrBlank()) return VARIANT_REQUIRED_MESSAGE
        if (type == TestType.FIXED_PANEL && variant.isNullOrBlank()) return VARIANT_REQUIRED_MESSAGE
        if (type == TestType.AUTHORED_DIFFICULTY &&
            Difficulty.entries.none { it.name == variant?.trim() }
        ) {
            return VARIANT_REQUIRED_MESSAGE
        }
        // Coverage's only mode is exposure weighting; any other variant would
        // silently run an unweighted sample under a different label.
        if (type == TestType.COVERAGE && !variant.isNullOrBlank() &&
            variant != EXPOSURE_VARIANT
        ) {
            return COVERAGE_VARIANT_MESSAGE
        }
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
        // A fixed panel is not a sampled length: it runs its whole frozen set,
        // even when a deleted question left a size that is not a step of ten.
        if (type == TestType.FIXED_PANEL) {
            return Resolution.Ready(
                count = available,
                short = available < MIN_LENGTH,
                clamped = count > available,
                message = if (count > available) onlyNMessage(available) else null
            )
        }
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
        const val VARIANT_REQUIRED_MESSAGE: String = "Choose a tag or difficulty for this test."
        const val COVERAGE_VARIANT_MESSAGE: String =
            "Coverage only supports the exposure-weighted mode."

        /**
         * The Coverage mode that prefers questions with fewer stored test
         * appearances (plan §10). It is a mode, not a type: the attempt still
         * reads as Coverage, and the composition records the weighting.
         */
        const val EXPOSURE_VARIANT: String = "EXPOSURE"
        const val PANEL_SCOPE_MESSAGE: String =
            "A fixed panel is a whole-paper set; clear the category selection to run it."

        /**
         * The largest shown count a sample may reach for [target] (section
         * 9.6): the exact target plus one block's overshoot, never above
         * [max]. This is the same limit [SessionOrder.sampledBounded] uses,
         * exposed so the setup sheet can refuse an impossible scope before it
         * emits a route.
         */
        fun samplerLimit(target: Int, max: Int = MAX_LENGTH): Int =
            minOf(max, target + maxOf(1, (target + 3) / 4))

        /** Whether a block-aware sample can start when the smallest block is [minBlockSize]. */
        fun samplerCanStart(target: Int, minBlockSize: Int, max: Int = MAX_LENGTH): Boolean =
            target <= 0 || minBlockSize <= samplerLimit(target, max)

        /**
         * Whether repeat avoidance leaves enough alternatives (section 9.8):
         * exclude only when the remaining pool is at least twice the request,
         * so a small pool is not distorted.
         */
        fun freshSampleFits(remaining: Int, requested: Int): Boolean =
            requested > 0 && remaining >= 2 * requested

        fun noPassageFitsMessage(): String =
            "No passage fits the requested length and overshoot limit; " +
                "choose a longer test or split a passage."

        fun passageTooLargeMessage(max: Int = MAX_LENGTH): String =
            "Every passage is larger than $max; split one or change the scope."

        /** The pure form of [maxAllowed]; callable without a plan. */
        fun maxAllowedFor(available: Int): Int {
            if (available <= 0) return 0
            if (available < MIN_LENGTH) return available
            val capped = minOf(available, MAX_LENGTH)
            return capped - capped % STEP
        }

        fun isAllowedLength(count: Int): Boolean =
            count in MIN_LENGTH..MAX_LENGTH && count % STEP == 0

        /**
         * The nearest selectable length to [value], for a value restored from
         * settings (plan §10): below 20 becomes 20, above 150 becomes 150, and
         * anything between snaps to the nearest step of ten. A corrupt or
         * legacy value therefore can never reach the plan unchanged.
         */
        fun normalizeLength(value: Int): Int {
            if (value <= MIN_LENGTH) return MIN_LENGTH
            if (value >= MAX_LENGTH) return MAX_LENGTH
            val index = ((value - MIN_LENGTH + STEP / 2) / STEP)
                .coerceIn(0, LENGTHS.lastIndex)
            return LENGTHS[index]
        }

        /** The stored form of a target set: sorted and deduplicated. */
        fun canonicalTargets(ids: Collection<String>): List<String> = ids.distinct().sorted()

        private fun onlyNMessage(available: Int): String =
            "Only $available questions in this scope."
    }
}
