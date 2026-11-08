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
    MIXED;

    /**
     * A mock measurement: the only types whose attempts feed the comparable
     * trend and the representative `rep*` counters (section 12.3). Everything
     * else is practice, whatever its label.
     */
    val isRepresentative: Boolean get() = this == RANDOM || this == BALANCED

    /** Practice types never claim mock comparability (section 11.5). */
    val isPractice: Boolean get() = !isRepresentative

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
