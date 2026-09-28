package com.mcqapp.domain

object Scoring {

    fun isCorrect(selected: Set<String>, correct: Set<String>): Boolean =
        selected.isNotEmpty() && selected == correct

    fun scoreQuestion(selected: Set<String>, correct: Set<String>, negativeMarking: Double): Double {
        if (selected.isEmpty()) return 0.0
        return if (isCorrect(selected, correct)) 1.0 else -negativeMarking
    }

    data class Summary(
        val correctCount: Int,
        val wrongCount: Int,
        val skippedCount: Int,
        val score: Double,
        val maxScore: Double
    )

    fun summarize(
        selections: Map<String, Set<String>>,
        questions: List<Question>,
        negativeMarking: Double
    ): Summary {
        var correct = 0
        var wrong = 0
        var skipped = 0
        var score = 0.0
        for (q in questions) {
            val selected = selections[q.id].orEmpty()
            when {
                selected.isEmpty() -> skipped++
                isCorrect(selected, q.correctOptionIds) -> {
                    correct++
                    score += 1.0
                }
                else -> {
                    wrong++
                    score -= negativeMarking
                }
            }
        }
        return Summary(correct, wrong, skipped, score, questions.size.toDouble())
    }
}
