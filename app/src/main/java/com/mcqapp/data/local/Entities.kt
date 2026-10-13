package com.mcqapp.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcqapp.domain.CardStateKind

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
    indices = [Index("categoryId"), Index("passageId")]
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    /**
     * The passage this question belongs to, when it shares context with
     * others. A plain nullable column without a foreign key (like
     * `bookmarks.questionId`): a passage's members may outlive a
     * still-resolving reorder, and passage deletion itself is refused
     * while members exist, so no cascade is wanted here.
     */
    val passageId: String? = null,
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
    foreignKeys = [
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("questionId"), Index("optionId")]
)
data class CorrectAnswerEntity(
    val questionId: String,
    val optionId: String
)

/**
 * Per-question memory state. Keyed by paper so a question reused in two
 * papers is scheduled independently, matching how the app already scopes
 * scores, marks and mistakes. [contentHash] mirrors the question's hash at
 * last review so an edit can reset the card instead of keeping a schedule
 * for text the learner never saw.
 */
@Entity(
    tableName = "card_state",
    primaryKeys = ["paperId", "questionId"],
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paperId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("questionId"), Index("dueAt")]
)
data class CardStateEntity(
    val paperId: String,
    val questionId: String,
    val ease: Double,
    val intervalDays: Int,
    val dueAt: Long,
    val reps: Int,
    val lapses: Int,
    val leech: Boolean,
    val lastReviewedAt: Long,
    val contentHash: String,
    /**
     * FSRS memory state (v10). [difficulty] is the 1..10 estimate of how hard
     * the material is for this learner and [stability] the days until recall
     * probability falls to the benchmark retention; both zero for a card that
     * has never been studied under FSRS.
     */
    val difficulty: Double = 0.0,
    val stability: Double = 0.0,
    val cardState: CardStateKind = CardStateKind.NEW,
    /** Total FSRS reviews ever applied. Mirrored into [reps]. */
    val reviewCount: Int = 0
)

/**
 * One graded study review, appended and never edited: the immutable history
 * the FSRS scheduler's future optimizer will need, and today's record of what
 * actually happened to a card. Deliberately local-only and autoincrement-keyed
 * (the `attempts` convention, not the spec's UUIDs), and scoped to the paper
 * like every other per-question row.
 */
@Entity(
    tableName = "review_log",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paperId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("questionId", "reviewedAt"), Index("paperId")]
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paperId: String,
    val questionId: String,
    /** 1=Again, 2=Hard, 3=Good, 4=Easy. */
    val rating: Int,
    val reviewedAt: Long,
    val elapsedSeconds: Long? = null,
    val stateBefore: String? = null,
    val stabilityAfter: Double? = null,
    val difficultyAfter: Double? = null,
    val intervalDaysAfter: Int? = null
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
