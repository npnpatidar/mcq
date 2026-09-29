package com.mcqapp.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "papers")
data class PaperEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String = "",
    val durationMinutes: Int = 0,
    val negativeMarking: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)

data class CategoryCountEntity(
    val categoryId: String,
    val cnt: Int
)

@Entity(
    tableName = "categories",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paperId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("paperId"), Index("parentId")]
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val paperId: String,
    val title: String,
    val parentId: String? = null,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("categoryId")]
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    val text: String,
    val image: String? = null,
    val explanation: String = "",
    val explanationImage: String? = null,
    val difficulty: String = "medium",
    val marks: Double = 1.0,
    val tags: String = "",
    val sortOrder: Int = 0,
    val contentHash: String = ""
)

@Entity(
    tableName = "options",
    primaryKeys = ["questionId", "id"],
    foreignKeys = [
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("questionId")]
)
data class OptionEntity(
    val questionId: String,
    val id: String,
    val text: String,
    val image: String? = null,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "correct_answers",
    primaryKeys = ["questionId", "optionId"],
    indices = [Index("optionId")]
)
data class CorrectAnswerEntity(
    val questionId: String,
    val optionId: String
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val questionId: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "attempts")
data class AttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
)

@Entity(
    tableName = "question_results",
    foreignKeys = [
        ForeignKey(
            entity = AttemptEntity::class,
            parentColumns = ["id"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("attemptId")]
)
data class QuestionResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attemptId: Long,
    val questionId: String,
    val categoryTitle: String,
    val text: String,
    val optionsJson: String,
    val correctOptionIds: String,
    val selectedOptionIds: String,
    val isCorrect: Boolean,
    val explanation: String,
    val explanationImage: String? = null,
    val dwellSeconds: Long = 0
)
