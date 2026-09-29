package com.mcqapp.domain

data class QuestionOption(
    val id: String,
    val text: String,
    val image: String? = null
)

enum class Difficulty(val label: String) {
    EASY("Easy"),
    MEDIUM("Medium"),
    HARD("Hard");

    companion object {
        fun fromLabel(label: String): Difficulty =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: MEDIUM
    }
}

data class Question(
    val id: String,
    val categoryId: String,
    val text: String,
    val image: String? = null,
    val options: List<QuestionOption>,
    val correctOptionIds: Set<String>,
    val explanation: String = "",
    val explanationImage: String? = null,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val marks: Double = 1.0,
    val tags: List<String> = emptyList()
) {
    val isMultiCorrect: Boolean get() = correctOptionIds.size > 1
}

data class CategoryNode(
    val id: String,
    val paperId: String,
    val title: String,
    val parentId: String?,
    val children: List<CategoryNode> = emptyList(),
    val questionCount: Int = 0
) {
    val totalQuestionCount: Int get() = questionCount + children.sumOf { it.totalQuestionCount }
}

data class Paper(
    val id: String,
    val title: String,
    val description: String = "",
    val durationMinutes: Int = 0,
    val negativeMarking: Double = 0.0,
    val categories: List<CategoryNode> = emptyList()
) {
    val totalQuestions: Int get() = categories.sumOf { it.totalQuestionCount }
}

data class Attempt(
    val id: Long,
    val paperId: String,
    val title: String,
    val totalQuestions: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val skippedCount: Int,
    val score: Double,
    val maxScore: Double,
    val durationSeconds: Long,
    val finishedAt: Long
) {
    val percentage: Double get() = if (maxScore > 0) score / maxScore * 100.0 else 0.0
}

data class QuestionResult(
    val questionId: String,
    val categoryTitle: String,
    val text: String,
    val options: List<QuestionOption>,
    val correctOptionIds: Set<String>,
    val selectedOptionIds: Set<String>,
    val isCorrect: Boolean,
    val explanation: String,
    val explanationImage: String? = null
)
