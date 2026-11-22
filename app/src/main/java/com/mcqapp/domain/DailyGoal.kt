package com.mcqapp.domain

import com.mcqapp.domain.stats.StatsAggregator

/**
 * The reporting-only daily question goal (owner ruling).
 *
 * It is a gauge, not a quota: nothing here is consulted by the study pull, the
 * test sampler, the queue order or the grade path. The only thing a goal does
 * is feed a label and a bar on Library and Statistics → Overview.
 *
 * Kept in the domain layer as pure JVM code so the two guarantees that matter —
 * sensible defaults and "exceeding the goal never blocks study" — are
 * executable in a normal unit test rather than only in a device test.
 */
object DailyGoal {
    /** Off by default: an existing learner's study flow must not change. */
    const val DEFAULT_ENABLED: Boolean = false

    /** The target offered when the switch is first turned on. */
    const val DEFAULT_COUNT: Int = 20
    const val MIN_COUNT: Int = 1
    const val MAX_COUNT: Int = 1000

    /** A typed or restored count outside the stepper's range is clamped. */
    fun normalizeCount(count: Int): Int = count.coerceIn(MIN_COUNT, MAX_COUNT)

    /**
     * One snapshot of the gauge. [reviews] is the number of reviews recorded in
     * the *current study day* (the 04:00 calendar boundary), including
     * self-graded cards; [count] is the learner's chosen target.
     */
    data class Progress(
        val reviews: Int,
        val count: Int,
        val enabled: Boolean
    ) {
        /** The effective target, even while the goal is switched off. */
        val target: Int get() = normalizeCount(count)

        /** 0..1 for the bar; the text still shows the real count past the target. */
        val fraction: Float
            get() = if (!enabled) 0f else (reviews.toFloat() / target).coerceIn(0f, 1f)

        val reached: Boolean get() = enabled && reviews >= target

        /** Going past the target is allowed and is reported honestly. */
        val exceeded: Boolean get() = enabled && reviews > target
    }

    fun progress(reviews: Int, count: Int, enabled: Boolean): Progress =
        Progress(reviews = reviews.coerceAtLeast(0), count = count, enabled = enabled)

    /**
     * The owner ruling, as an executable statement: the goal never gates study.
     * Every study entry point stays reachable at any progress, including past
     * the target; callers keep this as the single place that answers the
     * "can the learner keep studying?" question for the goal feature.
     */
    fun mayStudy(progress: Progress): Boolean = true

    /**
     * The one request Library and Statistics use for the personal goal. It is
     * deliberately unscoped: a paper selected in Statistics changes the charts,
     * never the learner's daily count. Both screens call this same helper, so
     * the two gauges cannot drift apart again.
     *
     * [load] is the existing unscoped stats query; [now] is injected so the
     * 04:00 study-day boundary is resolved from one clock per refresh.
     */
    suspend fun todayReviews(
        load: suspend (scopePaperId: String?, range: StatsAggregator.Range, now: Long) -> Int,
        now: Long
    ): Int = load(null, StatsAggregator.Range.D30, now)
}
