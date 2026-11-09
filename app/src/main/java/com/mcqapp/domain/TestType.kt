package com.mcqapp.domain

/**
 * The selectors a bounded Test can use (section 13, phase 1).
 *
 * The type is nullable wherever it is persisted: a pre-v21 attempt has no
 * type, and "unknown design" is not the same thing as RANDOM. [fromStorage]
 * therefore returns null for a null or unrecognised value instead of
 * inventing a type.
 */
enum class TestType {
    RANDOM,
    BALANCED,
    /**
     * Equal-allocation coverage: the same number from each selected topic
     * instead of a size-proportional split (plan §10). Practice only — it
     * over-represents small topics, so it is never comparable to Balanced and
     * never feeds the `rep*` mock counters.
     */
    COVERAGE,
    WEAKEST_TOPICS,
    TOUGHEST_QUESTIONS,
    EASIEST_QUESTIONS,
    MISTAKES,
    TRICKY,
    AT_RISK,
    UNSEEN,
    BOOKMARKED,
    TAG,
    AUTHORED_DIFFICULTY,
    SLOWEST,
    /** A saved, stable set of question ids, repeated to track progress on it. */
    FIXED_PANEL,
    MIXED;

    /**
     * A mock measurement: the only types whose attempts feed the comparable
     * trend and the representative `rep*` counters (section 12.3). Everything
     * else is practice, whatever its label.
     */
    val isRepresentative: Boolean get() = this == RANDOM || this == BALANCED

    /** Practice types never claim mock comparability (section 11.5). */
    val isPractice: Boolean get() = !isRepresentative

    /** The phase-2 practice selectors (stats.md §8.3–§8.9, §8.14). */
    val isFocus: Boolean
        get() = this == WEAKEST_TOPICS || this == TOUGHEST_QUESTIONS ||
            this == EASIEST_QUESTIONS || this == TRICKY || this == AT_RISK ||
            this == UNSEEN || this == MIXED

    /**
     * Whether the type is chosen by the signal selector. Balanced is
     * representative, so it is not a practice focus type, but its sample is
     * still allocated from the same read-only signals. The phase-3 custom
     * types draw their pool outside the signals (bookmarks, tags, dwell),
     * but their composition and fill still come from the one selector, so
     * they take this path too.
     */
    val usesSignalSelector: Boolean
        get() = isFocus || this == BALANCED || this == COVERAGE ||
            this == BOOKMARKED || this == TAG || this == AUTHORED_DIFFICULTY ||
            this == SLOWEST || this == FIXED_PANEL

    /** Only these types may record `exclusionMode = RECENT_3` (section 9.8). */
    val supportsFreshSample: Boolean
        get() = this == RANDOM || this == BALANCED || this == EASIEST_QUESTIONS

    /** Only these types record a target set (section 16 C3). */
    val supportsTargets: Boolean get() = this == WEAKEST_TOPICS || this == MIXED

    companion object {

        /**
         * The type stored as [raw], or null for a legacy row, an unknown value
         * or a null. Callers must not fall back to RANDOM.
         */
        fun fromStorage(raw: String?): TestType? {
            val value = raw?.trim() ?: return null
            return entries.firstOrNull { it.name == value }
        }

        /**
         * The stored names of the representative types. Repeat avoidance
         * (section 9.8) reads these as one family: a fresh sample avoids what
         * a strict Random or Balanced attempt asked, whichever of the two it
         * was.
         */
        fun representativeNames(): List<String> =
            entries.filter { it.isRepresentative }.map { it.name }
    }
}
