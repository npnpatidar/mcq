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
    val difficulty: String = "medium",
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
    val categories: List<CategoryDto> = emptyList()
)

@Serializable
data class McqFileDto(
    val version: Int = 1,
    val papers: List<PaperDto> = emptyList()
)
