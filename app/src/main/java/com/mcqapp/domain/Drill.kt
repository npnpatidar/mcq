package com.mcqapp.domain

import kotlin.random.Random

/**
 * Quick drills: a random sample of at most [count] questions (count <= 0
 * means the whole set) with its own clock. Sampling is seeded so a drill
 * layout is reproducible from logs; ids are untouched so scoring, review
 * and exports behave exactly like a normal test.
 */
object Drill {

    /**
     * Why a requested drill count cannot be run.
     *
     * [sample] quietly returns the whole set when the count exceeds what the
     * paper holds, so without this an ask for 999 questions on a 10-question
     * paper silently becomes a 10-question drill. The caller is expected to
     * refuse instead, so the user finds out before the drill starts.
     */
    sealed interface CountCheck {
        data object Ok : CountCheck
        /** The paper holds no questions, so there is nothing to sample. */
        data object NoQuestionsAvailable : CountCheck
        /** More questions were asked for than the paper contains. */
        data class TooManyForPaper(val requested: Int, val available: Int) : CountCheck
    }

    /** Whether [count] can be sampled from [available] questions. */
    fun checkCount(count: Int, available: Int): CountCheck = when {
        available <= 0 -> CountCheck.NoQuestionsAvailable
        count > available -> CountCheck.TooManyForPaper(count, available)
        else -> CountCheck.Ok
    }

    /** True when [count] can be sampled from [available] questions. */
    fun canSample(count: Int, available: Int): Boolean =
        checkCount(count, available) is CountCheck.Ok

    fun sample(questions: List<Question>, count: Int, seed: Long): List<Question> {
        if (count <= 0 || count >= questions.size) return questions
        return questions.shuffled(Random(seed)).take(count)
    }
}
