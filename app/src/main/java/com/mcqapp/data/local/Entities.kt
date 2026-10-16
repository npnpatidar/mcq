package com.mcqapp.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mcqapp.domain.CardStateKind

@Entity(tableName = "papers", indices = [Index("title")])
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
    // (paperId, title) covers getByPaper's per-paper scan and the title
    // match imports do; the paperId prefix replaces the old standalone index.
    indices = [Index("paperId", "title"), Index("parentId")]
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
    // contentHash: dedup probes it on every import and on the import
    // screen's duplicate classification; without the index each probe is a
    // full scan of the questions table (chunked thousands of times).
    indices = [Index("categoryId"), Index("passageId"), Index("contentHash")]
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
    // (paperId, cardState, dueAt) serves the per-paper, per-state due count
    // and eligibility aggregates; (paperId, dueAt) still serves the paper-wide
    // due range that never constrains the state.
    indices = [
        Index("questionId"),
        Index("paperId", "dueAt"),
        Index("paperId", "cardState", "dueAt")
    ]
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
    // (paperId, reviewedAt) covers the per-paper/per-day aggregates the
    // statistics screen and badges read; the bare paperId prefix of it
    // replaces the old standalone paperId index.
    indices = [Index("questionId", "reviewedAt"), Index("paperId", "reviewedAt")]
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
    // (attemptId, isCorrect) serves the results-screen tallies; the attemptId
    // prefix replaces the old standalone index.
    indices = [Index("attemptId", "isCorrect")]
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

/**
 * A question's lifetime performance across every stored attempt, maintained
 * as results are written so History's "hardest"/"weakest" cards are two
 * indexed reads instead of a whole-`question_results` scan. It is an
 * aggregate of all history, so deleting an attempt intentionally leaves it
 * untouched (matching the existing "hardest ever" semantics).
 */
@Entity(tableName = "question_stats")
data class QuestionStatsEntity(
    @PrimaryKey val questionId: String,
    val paperId: String,
    val paperTitle: String,
    val categoryTitle: String,
    val text: String,
    val totalAttempts: Int,
    val gradedCount: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val skipCount: Int,
    val ungradedCount: Int,
    val lastResultIsCorrect: Boolean,
    val lastFinishedAt: Long
)
