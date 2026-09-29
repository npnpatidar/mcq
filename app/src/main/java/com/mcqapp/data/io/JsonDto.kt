package com.mcqapp.data.io

import kotlinx.serialization.Serializable

@Serializable
data class OptionDto(
    val id: String,
    val text: String,
    val image: String? = null
)

@Serializable
data class QuestionDto(
    val id: String,
    val text: String,
    val image: String? = null,
    val options: List<OptionDto> = emptyList(),
    val correctOptionIds: List<String> = emptyList(),
    val explanation: String = "",
    val explanationImage: String? = null,
    val difficulty: String = "medium",
    val marks: Double = 1.0,
    val tags: List<String> = emptyList()
)

@Serializable
data class CategoryDto(
    val id: String,
    val title: String,
    val parentId: String? = null,
    val questions: List<QuestionDto> = emptyList()
)

@Serializable
data class PaperDto(
    val id: String,
    val title: String,
    val description: String = "",
    val durationMinutes: Int = 0,
    val negativeMarking: Double = 0.0,
    val categories: List<CategoryDto> = emptyList(),
    val questions: List<QuestionDto> = emptyList()
) {
    fun topLevelQuestions(): List<QuestionDto> = questions
}

@Serializable
data class AttemptResultDto(
    val questionId: String,
    val categoryTitle: String = "",
    val text: String = "",
    val optionsJson: String = "[]",
    val correctOptionIds: String = "",
    val selectedOptionIds: String = "",
    val isCorrect: Boolean = false,
    val explanation: String = "",
    val explanationImage: String? = null,
    val dwellSeconds: Long = 0
)

@Serializable
data class AttemptDto(
    val paperId: String,
    val title: String,
    val totalQuestions: Int = 0,
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val skippedCount: Int = 0,
    val score: Double = 0.0,
    val maxScore: Double = 0.0,
    val durationSeconds: Long = 0,
    val finishedAt: Long = 0,
    val results: List<AttemptResultDto> = emptyList()
)

@Serializable
data class McqFileDto(
    val version: Int = 1,
    val papers: List<PaperDto> = emptyList(),
    val bookmarks: List<String> = emptyList(),
    val attempts: List<AttemptDto> = emptyList()
)
