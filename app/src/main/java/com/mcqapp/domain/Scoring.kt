package com.mcqapp.domain

object Scoring {

    fun isCorrect(selected: Set<String>, correct: Set<String>): Boolean =
        selected.isNotEmpty() && selected == correct

    fun scoreQuestion(
        selected: Set<String>,
        correct: Set<String>,
        negativeMarking: Double,
        marks: Double = 1.0
    ): Double {
        if (correct.isEmpty()) return 0.0
        if (selected.isEmpty()) return 0.0
        return if (isCorrect(selected, correct)) marks else -marks * negativeMarking
    }

    data class Summary(
        val correctCount: Int,
        val wrongCount: Int,
        val skippedCount: Int,
        val score: Double,
        val maxScore: Double,
        val ungradedCount: Int = 0
    )

    fun summarize(
        selections: Map<String, Set<String>>,
        questions: List<Question>,
        negativeMarking: Double
    ): Summary {
        var correct = 0
        var wrong = 0
        var skipped = 0
        var ungraded = 0
        var score = 0.0
        var maxScore = 0.0
        for (q in questions) {
            if (q.correctOptionIds.isEmpty()) {
                ungraded++
                continue
            }
            maxScore += q.marks
            val selected = selections[q.id].orEmpty()
            when {
                selected.isEmpty() -> skipped++
                isCorrect(selected, q.correctOptionIds) -> {
                    correct++
                    score += q.marks
                }
                else -> {
                    wrong++
                    score -= q.marks * negativeMarking
                }
            }
        }
        return Summary(correct, wrong, skipped, score, maxScore, ungraded)
    }
}
