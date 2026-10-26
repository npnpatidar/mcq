package com.mcqapp.domain

/**
 * Quick drills: a random sample of at most the requested count (count <= 0
 * means the whole set) with its own clock. The live cut is [SessionOrder]'s
 * block-aware `sampled`, so passage blocks never split; ids are untouched so
 * scoring, review and exports behave exactly like a normal test.
 */
object Drill {

    /**
     * Why a requested drill count cannot be run.
     *
     * A quietly-smaller drill is how the count used to exceed the paper's
     * size without anyone noticing; the caller is expected to refuse instead,
     * so the user finds out before the drill starts.
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
}
