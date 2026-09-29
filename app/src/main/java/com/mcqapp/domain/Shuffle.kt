package com.mcqapp.domain

import kotlin.random.Random

/**
 * Deterministic shuffling for test attempts.
 *
 * A single [seed] drives one [Random] stream, so the question order and every
 * question's option order are jointly reproducible. Only ordering changes:
 * question/option ids are preserved, so selections, answer keys, results and
 * exports keep matching.
 */
object Shuffle {

    fun shuffleAttempt(
        questions: List<Question>,
        seed: Long,
        shuffleQuestions: Boolean,
        shuffleOptions: Boolean
    ): List<Question> {
        if (!shuffleQuestions && !shuffleOptions) return questions
        val random = Random(seed)
        val ordered = if (shuffleQuestions) questions.shuffled(random) else questions
        if (!shuffleOptions) return ordered
        return ordered.map { q -> q.copy(options = q.options.shuffled(random)) }
    }
}
