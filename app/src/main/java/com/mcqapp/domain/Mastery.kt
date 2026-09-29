package com.mcqapp.domain

/**
 * Per-category mastery across attempts: graded correct rate grouped by
 * (paper, category), weakest first. Ungraded rows never count for or
 * against a category.
 */
object Mastery {

    data class CategoryMastery(
        val paperId: String,
        val paperTitle: String,
        val categoryTitle: String,
        val correct: Int,
        val graded: Int
    ) {
        val rate: Double get() = if (graded > 0) correct.toDouble() / graded else 0.0
    }

    fun perCategory(
        attempts: List<Attempt>,
        results: List<QuestionResult>
    ): List<CategoryMastery> {
        val paperByAttempt = attempts.associate { it.id to it }
        val acc = mutableMapOf<Pair<String, String>, Pair<Int, Int>>()
        for (r in results) {
            if (r.correctOptionIds.isEmpty()) continue
            val attempt = paperByAttempt[r.attemptId] ?: continue
            val key = attempt.paperId to r.categoryTitle
            val (correct, graded) = acc[key] ?: (0 to 0)
            acc[key] = (correct + if (r.isCorrect) 1 else 0) to (graded + 1)
        }
        return acc.map { (key, counts) ->
            val attempt = attempts.first { it.paperId == key.first }
            CategoryMastery(
                paperId = key.first,
                paperTitle = attempt.title,
                categoryTitle = key.second,
                correct = counts.first,
                graded = counts.second
            )
        }
    }

    /** Weakest first: lowest rate, then most attempts, then titles. */
    fun weakest(mastery: List<CategoryMastery>, limit: Int = 5): List<CategoryMastery> =
        mastery.filter { it.graded > 0 }
            .sortedWith(
                compareBy<CategoryMastery> { it.rate }
                    .thenByDescending { it.graded }
                    .thenBy { it.paperTitle }
                    .thenBy { it.categoryTitle }
            ).take(limit)
}
