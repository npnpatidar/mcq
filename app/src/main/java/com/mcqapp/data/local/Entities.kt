package com.mcqapp.data.local

import androidx.room.ColumnInfo
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
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * The library's manual order, ascending; `createdAt DESC` breaks ties.
     * New papers take the current minimum - 1 so they land at the top without
     * rewriting every other row, and a reorder normalises the sequence back to
     * 0..n-1 (see PaperStore.movePaper). Pre-v20 rows share 0 and therefore
     * keep their old newest-first order until the user moves one.
     */
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0L
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
    // (paperId, sortOrder, title) additionally carries the paper-wide category
    // order Browse and the drawer list in, so the section order is read from
    // the index instead of sorted into a temp B-tree.
    indices = [
        Index("paperId", "title"),
        Index("paperId", "sortOrder", "title"),
        Index("parentId")
    ]
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
    // (categoryId, sortOrder) replaces the standalone categoryId index — it
    // serves every one of those lookups and also carries the per-category
    // order (and its rowid tie-break, which SQLite appends) that Browse pages
    // by, so the sort comes from the index instead of a whole-paper temp
    // B-tree.
    indices = [Index("categoryId", "sortOrder"), Index("passageId"), Index("contentHash")]
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
    /**
     * Flattened, markup-free visible text of [text]'s elements
     * ([com.mcqapp.domain.searchableText]), maintained at write time.
     *
     * Global search prefilters with `LIKE '%q%'`, which no index can serve, so
     * it used to scan the elements *JSON* (markup, formulas and all) for every
     * row. Matching this plain column instead is exact — it is the very string
     * the in-memory [com.mcqapp.domain.QuestionSearch.filter] tests — and much
     * cheaper per row. No default: every writer must set it, so a new write
     * path cannot silently store an empty, unsearchable row.
     */
    val searchText: String,
    /**
     * [searchText] case-folded with Kotlin's full-Unicode [String.lowercase]
     * at write time. The SQL `LIKE` prefilter matches *this* column against a
     * Kotlin-lowercased query so non-ASCII literals (`CAFÉ` vs `café`,
     * Cyrillic, Greek) fold the same way the in-memory
     * [com.mcqapp.domain.QuestionSearch.filter] folds — SQLite's own
     * `lower()` and `LIKE` fold A–Z only. No Kotlin default: every writer
     * must set it, like [searchText].
     */
    @ColumnInfo(defaultValue = "''")
    val searchTextFolded: String,
    val image: String? = null,
    val explanation: String = "",
    val explanationImage: String? = null,
    val difficulty: String = "medium",
    val marks: Double = 1.0,
    val tags: String = "",
    /**
     * [tags] folded exactly like [searchTextFolded]: the tag branches of the
     * search/prefilter queries match a folded query against it, so a `CAFÉ`
     * tag is found by `café` and vice versa.
     */
    @ColumnInfo(defaultValue = "''")
    val tagsFolded: String,
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
    /** Flattened text of [text], as on [QuestionEntity.searchText]. */
    val searchText: String,
    /**
     * [searchText] folded like [QuestionEntity.searchTextFolded]: the
     * option branch of every search/prefilter query matches a
     * Kotlin-lowercased query against this column.
     */
    @ColumnInfo(defaultValue = "''")
    val searchTextFolded: String,
    val image: String? = null,
    val sortOrder: Int = 0
)

/**
 * The exact-tag index derived from [QuestionEntity.tags] (phase 3).
 *
 * The CSV stays canonical for search and export; this table exists so the
 * Tag test can filter on whole tags instead of a `LIKE '%tag%'` scan. Every
 * write of `tags` rewrites the rows through [com.mcqapp.domain.TagNormalizer].
 * Cascading with the question keeps the index exact across delete.
 */
@Entity(
    tableName = "question_tags",
    primaryKeys = ["questionId", "tagFolded"],
    foreignKeys = [
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagFolded", "questionId")]
)
data class QuestionTagEntity(
    val questionId: String,
    val tag: String,
    val tagFolded: String
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
        Index("paperId", "cardState", "dueAt"),
        // Leech/at-risk selection reads a paper's leeches by due date.
        Index("paperId", "leech", "dueAt")
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
    // replaces the old standalone paperId index. The triple additionally
    // serves the calibration pairing read, which walks one card's history in
    // order: (paperId, reviewId) alone would sort into a temp B-tree.
    indices = [
        Index("questionId", "reviewedAt"),
        Index("paperId", "reviewedAt"),
        Index("paperId", "questionId", "reviewedAt")
    ]
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
    val intervalDaysAfter: Int? = null,
    /**
     * Whether the learner declared the attempt a guess before answering.
     * Null for rows written before v19: those reviews genuinely carry no flag,
     * and defaulting them to false would claim every old answer was earned.
     */
    val wasGuess: Boolean? = null,
    /** Whether the chosen options matched the key, at answer time. Null pre-v19. */
    val wasCorrect: Boolean? = null,
    /**
     * The grade the app proposed before any edit (v26). Null in classic mode,
     * which has no proposal. Kept beside [gradeSource] because the two differ
     * in exactly one case: the learner changes the grade and changes it back,
     * where saved equals proposed but the source is still EDITED.
     */
    val proposedRating: Int? = null,
    /** AUTO, EDITED or CHOSEN (v26). Null on rows written before v26. */
    val gradeSource: String? = null,
    /** The ladder version in force; 0 means "written before versioning". */
    @ColumnInfo(defaultValue = "0")
    val ladderVersion: Int = 0,
    /** FSRS retrievability computed immediately before this answer (v26). */
    val predictedRecall: Double? = null,
    /** Which weight set produced [predictedRecall]. 0 = shipped defaults. */
    val fsrsFitVersion: Int? = null,
    /** The reading allowance that was subtracted, in seconds (v26). */
    val readingAllowanceSeconds: Int? = null,
    /** The attempt was submitted with an empty selection, not answered (v26). */
    val wasSkipped: Boolean? = null
)

/**
 * One row per change of the grading ladder's cut points (v26).
 *
 * The cuts themselves still live in DataStore, where the scheduler reads
 * them; this table is the audit trail that lets a review row say which
 * version graded it. A version can exist with no evidence block (`prior`, a
 * manual edit, a restore) and a block can exist with no version (a closed
 * window that decided nothing), which is why they are separate tables.
 */
@Entity(tableName = "ladder_versions", indices = [Index("effectiveFrom")])
data class LadderVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val easySeconds: Long,
    val slowSeconds: Long,
    val effectiveFrom: Long,
    /** prior, manual, proposal, automatic or rollback. */
    val reason: String,
    /** The version this one replaced; null for a seed. */
    val previousId: Long? = null,
    /** The evidence block that triggered the change; null for seeds and manual edits. */
    val blockId: Long? = null,
    /** Which build of the decision rule made the call. */
    val policyVersion: Int = 0
)

/**
 * One closed evidence window of the grading ladder (v26), including windows
 * that decided nothing.
 *
 * Written even when the outcome is no-change or insufficient-evidence: at
 * this volume nearly every window declines to act, and a controller whose
 * null decisions are stored nowhere is indistinguishable from a stalled one.
 * The rollback rule needs two consecutive post-change blocks, neither of
 * which owns a version row, which is the second reason this is its own table.
 */
@Entity(tableName = "ladder_blocks", indices = [Index("startAt")])
data class LadderBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startAt: Long,
    val endAt: Long,
    val ladderVersion: Int,
    val fsrsFitVersion: Int? = null,
    val policyVersion: Int = 0,
    val easyPairs: Int = 0,
    val easyCards: Int = 0,
    val goodPairs: Int = 0,
    val goodCards: Int = 0,
    val hardPairs: Int = 0,
    val hardCards: Int = 0,
    val delta1: Double? = null,
    val delta1Low: Double? = null,
    val delta1High: Double? = null,
    val delta2: Double? = null,
    val delta2Low: Double? = null,
    val delta2High: Double? = null,
    val followUpEasy: Double? = null,
    val followUpGood: Double? = null,
    /** no-change, raise-c1, lower-c1, insufficient-evidence or frozen-refit. */
    val outcome: String,
    val decidedAt: Long? = null
)

/**
 * One sitting on the study screen (v26): real study time, not question time.
 *
 * Cascades with its paper. Without that, deleting a paper would leave orphan
 * hours in the "lapses per hour" denominator while review_log (which also
 * cascades) lost the lapses — tidying the library would improve the metric.
 */
@Entity(
    tableName = "study_sessions",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paperId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("paperId", "startedAt")]
)
data class StudySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paperId: String,
    val startedAt: Long,
    val endedAt: Long,
    val activeSeconds: Long
)

/**
 * One question's progress inside an in-progress test session: what was
 * selected, whether the answer was revealed or the question flagged, and the
 * time spent on it.
 *
 * Stored a row at a time so an answer costs one small upsert. The resume
 * snapshot used to carry every answer in one JSON document, so each tap
 * re-serialised everything answered so far — money that grows with the sitting,
 * not with the tap. The snapshot now keeps only what a session *is* (seeds,
 * scope, index, timers) and the answers live here.
 *
 * Scoped to the paper, cascading with it: deleting a paper drops its session,
 * and re-importing one replaces the paper row, which does the same. [selection]
 * is option ids comma-joined exactly like `question_results.selectedOptionIds`,
 * empty when the question was reached but nothing was picked.
 */
@Entity(
    tableName = "test_progress",
    primaryKeys = ["paperId", "questionId"],
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paperId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TestProgressEntity(
    val paperId: String,
    val questionId: String,
    val selection: String = "",
    val revealed: Boolean = false,
    val flagged: Boolean = false,
    val dwellSeconds: Long = 0L
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val questionId: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "attempts",
    // The comparability key (section 11.5) in one index: a trend lookup filters
    // on every leading column and orders by finishedAt.
    indices = [
        Index(
            "paperId", "testType", "requestedCount", "scopeKey",
            "samplingVersion", "exclusionMode", "finishedAt"
        )
    ]
)
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
    val finishedAt: Long,
    /**
     * The bounded-Test metadata (section 12.1). Every default is the legacy
     * value: a pre-v21 row has no type, no requested count and sampling
     * version 1, so it is never comparable and never touches `rep*`. `null`
     * type means "unknown design", never RANDOM.
     */
    val testType: String? = null,
    val requestedCount: Int = 0,
    val scopeCategoryIds: String = "",
    val scopeKey: String = "",
    val samplingVersion: Int = 1,
    val seed: Long = 0,
    val exclusionMode: String = "NONE",
    val targetTopicIds: String = "",
    val compositionJson: String = "",
    /**
     * The custom type's choice (phase 3): the tag or difficulty name a Tag /
     * Authored difficulty attempt started from. Null for every other type and
     * for every pre-v22 row.
     */
    val variant: String? = null,
    /**
     * The negative-marking rate the attempt actually applied. Stored rather
     * than re-derived: questions carry their own marks, so the old
     * `(correct - score) / wrong` reconstruction silently assumed one mark
     * per question. Null on pre-v24 rows, which keep the old fallback.
     */
    val negativeMarking: Double? = null
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
    //
    // (questionId, id) is declared here as an ordinary index only because
    // Room's schema validation compares the complete index set and @Index
    // cannot express a WHERE clause. The real index in the database is the
    // partial `... WHERE selectedOptionIds != ''` built by
    // AppDatabase.PARTIAL_RESULT_INDEX_SQL; SQLite reports the same name and
    // columns, so validation passes. Keep this name and column list in sync
    // with that statement.
    indices = [
        Index("attemptId", "isCorrect"),
        Index("questionId", "id")
    ]
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
 * A question's performance across every attempt still stored, maintained as
 * results are written so History's "hardest"/"weakest" cards are indexed reads
 * instead of a whole-`question_results` scan.
 *
 * It means "currently stored", not "ever" (section 3.1): deleting an attempt
 * recomputes the affected rows from the surviving results, and a row with no
 * surviving result is dropped. [repCorrect], [repWrong] and [repSkip] count
 * only representative attempts (Random/Balanced with `samplingVersion >= 2`);
 * `repGraded` is derived at read so it cannot drift from the three counters.
 */
@Entity(tableName = "question_stats", indices = [Index("paperId")])
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
    val lastFinishedAt: Long,
    val repCorrect: Int = 0,
    val repWrong: Int = 0,
    val repSkip: Int = 0
)

/**
 * A fixed panel: a named, stable set of question ids, so progress on the same
 * questions can be read across attempts without resampling noise. The ids are
 * frozen when the panel is created; editing the bank does not silently swap
 * them (a deleted question cascades out and shrinks the panel instead).
 */
@Entity(
    tableName = "panels",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paperId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("paperId", "name")]
)
data class PanelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paperId: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** One frozen member of a [PanelEntity], in the order the panel asks it. */
@Entity(
    tableName = "panel_questions",
    primaryKeys = ["panelId", "questionId"],
    foreignKeys = [
        ForeignKey(
            entity = PanelEntity::class,
            parentColumns = ["id"],
            childColumns = ["panelId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = QuestionEntity::class,
            parentColumns = ["id"],
            childColumns = ["questionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("panelId", "position"), Index("questionId")]
)
data class PanelQuestionEntity(
    val panelId: Long,
    val questionId: String,
    val position: Int
)

/**
 * A named weighting of a paper's categories (exam blueprint): a tree of
 * relative parts over category nodes. Device state, like panels — a shared
 * deck must not carry another library's blueprints. The broker's unique
 * (paperId, name) index keeps two chips from reading the same label.
 */
@Entity(
    tableName = "blueprints",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["id"],
            childColumns = ["paperId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["paperId", "name"], unique = true)]
)
data class BlueprintEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paperId: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * One section of a [BlueprintEntity]: a category node, its local weight, and
 * an optional parent section forming the blueprint tree. Deleting a category
 * cascades the section away (and, via the self-referencing parent pointer,
 * its whole blueprint subtree); moving a category to another paper leaves
 * the row and reports it unavailable in views.
 */
@Entity(
    tableName = "blueprint_sections",
    foreignKeys = [
        ForeignKey(
            entity = BlueprintEntity::class,
            parentColumns = ["id"],
            childColumns = ["blueprintId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BlueprintSectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentSectionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["blueprintId", "categoryId"], unique = true),
        Index("blueprintId"),
        Index("parentSectionId"),
        Index("categoryId")
    ]
)
data class BlueprintSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blueprintId: Long,
    val categoryId: String,
    val parentSectionId: Long? = null,
    val parts: Int
)
