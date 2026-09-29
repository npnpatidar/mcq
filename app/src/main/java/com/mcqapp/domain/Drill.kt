package com.mcqapp.domain

import kotlin.random.Random

/**
 * Quick drills: a random sample of at most [count] questions (count <= 0
 * means the whole set) with its own clock. Sampling is seeded so a drill
 * layout is reproducible from logs; ids are untouched so scoring, review
 * and exports behave exactly like a normal test.
 */
object Drill {

    fun sample(questions: List<Question>, count: Int, seed: Long): List<Question> {
        if (count <= 0 || count >= questions.size) return questions
        return questions.shuffled(Random(seed)).take(count)
    }
}
