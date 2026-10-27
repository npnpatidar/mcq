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

    /**
     * One question's options in a session's order, derived from the question
     * id rather than from a position in a stream.
     *
     * Study pulls its cards one at a time, so there is no stable position to
     * count from; hashing the id gives every question its own order that the
     * same [sessionSeed] reproduces. A card revealed, left and pulled again
     * must not rearrange its options under the reader's selection.
     *
     * Seed 0 means the setting is off; a real seed is drawn per sitting, never
     * 0, so "off" needs no separate flag here.
     */
    fun optionsFor(question: Question, sessionSeed: Long): Question {
        if (sessionSeed == 0L || question.options.size <= 1) return question
        return question.copy(
            options = question.options.shuffled(
                Random(sessionSeed xor question.id.hashCode().toLong())
            )
        )
    }
}
