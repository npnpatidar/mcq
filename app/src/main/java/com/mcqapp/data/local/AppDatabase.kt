package com.mcqapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mcqapp.domain.TagNormalizer
import com.mcqapp.domain.parseContentElements
import com.mcqapp.domain.passageSearchText
import com.mcqapp.domain.searchableText

@Database(
    entities = [
        PaperEntity::class,
        CategoryEntity::class,
        QuestionEntity::class,
        OptionEntity::class,
        CorrectAnswerEntity::class,
        QuestionTagEntity::class,
        BookmarkEntity::class,
        AttemptEntity::class,
        QuestionResultEntity::class,
        QuestionStatsEntity::class,
        CardStateEntity::class,
        ReviewLogEntity::class,
        PassageEntity::class,
        TestProgressEntity::class,
        PanelEntity::class,
        PanelQuestionEntity::class
    ],
    version = 24,
    // From v11 on the schema is exported to app/schemas so every index and
    // column change is reviewable as a diff rather than discovered by a
    // migration failure on a user's device.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun paperDao(): PaperDao
    abstract fun categoryDao(): CategoryDao
    abstract fun questionDao(): QuestionDao
    abstract fun optionDao(): OptionDao
    abstract fun correctAnswerDao(): CorrectAnswerDao
    abstract fun questionTagDao(): QuestionTagDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun attemptDao(): AttemptDao
    abstract fun questionStatsDao(): QuestionStatsDao
    abstract fun cardStateDao(): CardStateDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun passageDao(): PassageDao
    abstract fun testProgressDao(): TestProgressDao
    abstract fun panelDao(): PanelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN explanationImage TEXT")
                db.execSQL("ALTER TABLE question_results ADD COLUMN explanationImage TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN marks REAL NOT NULL DEFAULT 1.0")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE question_results ADD COLUMN dwellSeconds INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card_state` (" +
                        "`paperId` TEXT NOT NULL, " +
                        "`questionId` TEXT NOT NULL, " +
                        "`ease` REAL NOT NULL, " +
                        "`intervalDays` INTEGER NOT NULL, " +
                        "`dueAt` INTEGER NOT NULL, " +
                        "`reps` INTEGER NOT NULL, " +
                        "`lapses` INTEGER NOT NULL, " +
                        "`leech` INTEGER NOT NULL, " +
                        "`lastReviewedAt` INTEGER NOT NULL, " +
                        "`contentHash` TEXT NOT NULL, " +
                        "PRIMARY KEY(`paperId`, `questionId`), " +
                        "FOREIGN KEY(`paperId`) REFERENCES `papers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_state_questionId` " +
                        "ON `card_state` (`questionId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_state_dueAt` " +
                        "ON `card_state` (`dueAt`)"
                )
            }
        }

        /**
         * Adds the `correct_answers -> questions` cascade and drops answer-key
         * rows whose question is already gone. SQLite cannot add a foreign key
         * to an existing table, so the table is rebuilt carrying only rows
         * that still have a parent question.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `correct_answers_new` (" +
                        "`questionId` TEXT NOT NULL, `optionId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`questionId`, `optionId`), " +
                        "FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO `correct_answers_new` (`questionId`, `optionId`) " +
                        "SELECT `correct_answers`.`questionId`, `correct_answers`.`optionId` " +
                        "FROM `correct_answers` INNER JOIN `questions` " +
                        "ON `questions`.`id` = `correct_answers`.`questionId`"
                )
                db.execSQL("DROP TABLE `correct_answers`")
                db.execSQL("ALTER TABLE `correct_answers_new` RENAME TO `correct_answers`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_correct_answers_questionId` " +
                        "ON `correct_answers` (`questionId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_correct_answers_optionId` " +
                        "ON `correct_answers` (`optionId`)"
                )
            }
        }

        /**
         * Adds the `passages` table and the nullable `questions.passageId`
         * column. Both are additive: nothing existing changes shape, so the
         * migration is two CREATE/ALTER statements and every v8 row survives
         * verbatim with `passageId = NULL` (standalone questions, exactly what
         * they were).
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `passages` (" +
                        "`id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `image` TEXT, `sortOrder` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_passages_categoryId` " +
                        "ON `passages` (`categoryId`)"
                )
                db.execSQL("ALTER TABLE questions ADD COLUMN passageId TEXT")
                // Four queries filter on passageId (member lookups, the
                // last-member anchor, member counts, the search union); the
                // index turns each from a full scan into an index seek.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_questions_passageId` " +
                        "ON `questions` (`passageId`)"
                )
            }
        }

        /**
         * Moves scheduling to FSRS (v10): the memory columns land on
         * `card_state`, the immutable `review_log` table is created, and every
         * SM-2 schedule is wiped. The wipe is the decision, not a side effect:
         * SM-2 ease/interval numbers are meaningless to the FSRS model, so
         * carrying them over would fake a memory state no formula produced.
         * Attempt history does not re-seed (that path is deleted), so the
         * blank slate survives; JSON and Anki imports remain the only
         * warm-start vectors.
         *
         * `rating` is INTEGER, not the plan's SMALLINT — SQLite gives SMALLINT
         * INTEGER affinity anyway, and INTEGER is what Room expects for an
         * Int field, so validation passes without a type converter.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE card_state ADD COLUMN difficulty REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE card_state ADD COLUMN stability REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE card_state ADD COLUMN cardState TEXT NOT NULL DEFAULT 'NEW'")
                db.execSQL("ALTER TABLE card_state ADD COLUMN reviewCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DELETE FROM card_state")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `review_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`paperId` TEXT NOT NULL, " +
                        "`questionId` TEXT NOT NULL, " +
                        "`rating` INTEGER NOT NULL, " +
                        "`reviewedAt` INTEGER NOT NULL, " +
                        "`elapsedSeconds` INTEGER, " +
                        "`stateBefore` TEXT, " +
                        "`stabilityAfter` REAL, " +
                        "`difficultyAfter` REAL, " +
                        "`intervalDaysAfter` INTEGER, " +
                        "FOREIGN KEY(`paperId`) REFERENCES `papers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_review_log_questionId_reviewedAt` " +
                        "ON `review_log` (`questionId`, `reviewedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_review_log_paperId` " +
                        "ON `review_log` (`paperId`)"
                )
            }
        }

        /**
         * Indexes the large-collection queries that scanned before: dedup
         * probes `questions.contentHash` on every import and preview
         * refresh, the study/library counts filter `card_state` by paper
         * and due time, and the statistics aggregates group `review_log` by
         * paper and day. Pure index additions — no row moves, every table
         * keeps its shape.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_questions_contentHash` " +
                        "ON `questions` (`contentHash`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_state_paperId_dueAt` " +
                        "ON `card_state` (`paperId`, `dueAt`)"
                )
                // The composite replaces the standalone paperId index: its
                // paperId prefix serves every query the old one did.
                db.execSQL("DROP INDEX IF EXISTS `index_review_log_paperId`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_review_log_paperId_reviewedAt` " +
                        "ON `review_log` (`paperId`, `reviewedAt`)"
                )
            }
        }

        /**
         * The remaining large-collection indexes: per-paper/per-state due
         * counts (`card_state`), results tallies (`question_results`), and the
         * title lookups imports and the paper list do (`papers`, `categories`).
         * Index-only — no table changes shape, every row survives verbatim.
         * The two composite indexes replace their standalone prefixes, which
         * are dropped here.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_papers_title` " +
                        "ON `papers` (`title`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_state_paperId_cardState_dueAt` " +
                        "ON `card_state` (`paperId`, `cardState`, `dueAt`)"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_question_results_attemptId`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_question_results_attemptId_isCorrect` " +
                        "ON `question_results` (`attemptId`, `isCorrect`)"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_categories_paperId`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_categories_paperId_title` " +
                        "ON `categories` (`paperId`, `title`)"
                )
            }
        }

        /**
         * Adds the `question_stats` lifetime aggregate and backfills it from
         * `question_results` in one grouped statement. History's
         * "hardest"/"weakest" cards become two indexed reads instead of
         * loading every result row. Deleting an attempt intentionally does
         * not shrink these counters (they are an all-time aggregate).
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `question_stats` (" +
                        "`questionId` TEXT NOT NULL, `paperId` TEXT NOT NULL, " +
                        "`paperTitle` TEXT NOT NULL, `categoryTitle` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `totalAttempts` INTEGER NOT NULL, " +
                        "`gradedCount` INTEGER NOT NULL, `correctCount` INTEGER NOT NULL, " +
                        "`wrongCount` INTEGER NOT NULL, `skipCount` INTEGER NOT NULL, " +
                        "`ungradedCount` INTEGER NOT NULL, `lastResultIsCorrect` INTEGER NOT NULL, " +
                        "`lastFinishedAt` INTEGER NOT NULL, PRIMARY KEY(`questionId`))"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO question_stats " +
                        "(questionId, paperId, paperTitle, categoryTitle, text, " +
                        "totalAttempts, gradedCount, correctCount, wrongCount, skipCount, " +
                        "ungradedCount, lastResultIsCorrect, lastFinishedAt) " +
                        "SELECT r.questionId, a.paperId, a.title, r.categoryTitle, r.text, " +
                        "c.total, c.graded, c.correct, c.wrong, c.skip, c.ungraded, " +
                        "r.isCorrect, a.finishedAt " +
                        "FROM question_results r " +
                        "INNER JOIN attempts a ON a.id = r.attemptId " +
                        "INNER JOIN (" +
                        "SELECT questionId, COUNT(*) AS total, " +
                        "SUM(CASE WHEN correctOptionIds != '' THEN 1 ELSE 0 END) AS graded, " +
                        "SUM(CASE WHEN correctOptionIds != '' AND selectedOptionIds != '' " +
                        "AND isCorrect THEN 1 ELSE 0 END) AS correct, " +
                        "SUM(CASE WHEN correctOptionIds != '' AND selectedOptionIds != '' " +
                        "AND isCorrect = 0 THEN 1 ELSE 0 END) AS wrong, " +
                        "SUM(CASE WHEN correctOptionIds != '' AND selectedOptionIds = '' " +
                        "THEN 1 ELSE 0 END) AS skip, " +
                        "SUM(CASE WHEN correctOptionIds = '' THEN 1 ELSE 0 END) AS ungraded, " +
                        "MAX(rowid) AS maxRow FROM question_results GROUP BY questionId" +
                        ") c ON c.questionId = r.questionId " +
                        "WHERE r.rowid = c.maxRow"
                )
            }
        }

        /**
         * Rebuilds `card_state` to match the entity exactly. Two pieces of
         * migration fallout are removed in one table rebuild (SQLite cannot
         * drop a column default in place):
         *
         * - the four FSRS columns kept the `DEFAULT` that ALTER TABLE ADD
         *   COLUMN required in 9->10, which Room reports as a schema drift;
         * - `index_card_state_dueAt` is left over from 6->7, redundant now
         *   that `(paperId, dueAt)` and `(paperId, cardState, dueAt)` exist.
         *
         * Every row survives verbatim; dropping the table also drops the
         * stale index, and the three current indexes are recreated.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card_state_new` (" +
                        "`paperId` TEXT NOT NULL, `questionId` TEXT NOT NULL, " +
                        "`ease` REAL NOT NULL, `intervalDays` INTEGER NOT NULL, " +
                        "`dueAt` INTEGER NOT NULL, `reps` INTEGER NOT NULL, " +
                        "`lapses` INTEGER NOT NULL, `leech` INTEGER NOT NULL, " +
                        "`lastReviewedAt` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, " +
                        "`difficulty` REAL NOT NULL, `stability` REAL NOT NULL, " +
                        "`cardState` TEXT NOT NULL, `reviewCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`paperId`, `questionId`), " +
                        "FOREIGN KEY(`paperId`) REFERENCES `papers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO `card_state_new` (`paperId`, `questionId`, `ease`, " +
                        "`intervalDays`, `dueAt`, `reps`, `lapses`, `leech`, `lastReviewedAt`, " +
                        "`contentHash`, `difficulty`, `stability`, `cardState`, `reviewCount`) " +
                        "SELECT `paperId`, `questionId`, `ease`, `intervalDays`, `dueAt`, " +
                        "`reps`, `lapses`, `leech`, `lastReviewedAt`, `contentHash`, " +
                        "`difficulty`, `stability`, `cardState`, `reviewCount` FROM `card_state`"
                )
                db.execSQL("DROP TABLE `card_state`")
                db.execSQL("ALTER TABLE `card_state_new` RENAME TO `card_state`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_state_questionId` " +
                        "ON `card_state` (`questionId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_state_paperId_dueAt` " +
                        "ON `card_state` (`paperId`, `dueAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_state_paperId_cardState_dueAt` " +
                        "ON `card_state` (`paperId`, `cardState`, `dueAt`)"
                )
            }
        }

        /**
         * Adds the flattened `searchText` columns (F4). Global search
         * prefilters with `LIKE '%q%'`, which no index can serve, so it used
         * to scan the elements *JSON* — markup, formulas and all — for every
         * row. Matching the plain visible text instead is exact (it is the
         * same string the in-memory filter tests) and much cheaper per row.
         *
         * Existing rows are backfilled from the JSON already on disk.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `questions` ADD COLUMN `searchText` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `options` ADD COLUMN `searchText` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `passages` ADD COLUMN `searchText` TEXT NOT NULL DEFAULT ''"
                )
                backfillSearchText(db)
            }
        }

        /** Rows filled per backfill round trip. */
        private const val SEARCH_BACKFILL_BATCH = 2000

        /**
         * Adds the two indices Browse pages by, and drops the one the second
         * replaces.
         *
         * Browse orders a paper's questions by
         * *(category sortOrder, category title, question sortOrder, rowid)*.
         * With no index providing that order, every page was the whole paper
         * sorted into a temp B-tree before the LIMIT could throw the rest
         * away — measured at a million questions, the first page of 60 rows
         * took 2.3 s. With the two indices below the same page is ~5 ms,
         * because the walk starts in category order and stops 60 rows later.
         *
         * `(categoryId, sortOrder)` also serves everything the standalone
         * `index_questions_categoryId` did (it is the leftmost prefix), so
         * that one is dropped rather than paid for twice on every import.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_categories_paperId_sortOrder_title` " +
                        "ON `categories` (`paperId`, `sortOrder`, `title`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_questions_categoryId_sortOrder` " +
                        "ON `questions` (`categoryId`, `sortOrder`)"
                )
                db.execSQL("DROP INDEX IF EXISTS `index_questions_categoryId`")
            }
        }

        /**
         * Adds the folded search columns and fills them from the flattened
         * `searchText` already stored. `LIKE` — and SQLite's own `lower()` —
         * case-fold A–Z only, so a query typed `café` could never match a
         * stored `CAFÉ`, even though the in-memory authority (and the UI
         * contract "case-insensitive") folds with full Unicode
         * [String.lowercase]: the prefilter vetoed the row before the
         * authority ever saw it. The search/prefilter branches now match a
         * Kotlin-lowercased query against these Kotlin-folded columns
         * (questions' stem text + tags, options, passages), so the SQL pass
         * agrees with the authority on every letter that matters.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("questions", "options", "passages")) {
                    db.execSQL(
                        "ALTER TABLE `$table` ADD COLUMN `searchTextFolded` " +
                            "TEXT NOT NULL DEFAULT ''"
                    )
                }
                db.execSQL(
                    "ALTER TABLE `questions` ADD COLUMN `tagsFolded` " +
                        "TEXT NOT NULL DEFAULT ''"
                )
                backfillFolded(db)
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nullable, no default: pre-v19 rows have no flag to copy, and
                // an INTEGER default of 0 would claim every old answer was
                // earned. The guessing statistics count flagged rows only.
                db.execSQL("ALTER TABLE `review_log` ADD COLUMN `wasGuess` INTEGER")
                db.execSQL("ALTER TABLE `review_log` ADD COLUMN `wasCorrect` INTEGER")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Default 0 for every existing paper: they keep the old
                // createdAt DESC order until the user reorders one, at which
                // point PaperStore.movePaper normalises the sequence.
                db.execSQL(
                    "ALTER TABLE `papers` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * The partial index over graded, answered results. Room's `@Index`
         * cannot express a `WHERE` clause, so it is raw SQL — and it needs
         * two creation paths: a migration that only ran `ALTER TABLE` would
         * leave a fresh install without it, and a callback alone would leave
         * upgraded installs without it. Both paths execute this same
         * statement.
         *
         * Room 2.8.5 compares the *complete* index set during schema
         * validation, so [QuestionResultEntity] declares the same name and
         * columns as an ordinary index. On an upgrade the migration creates
         * the partial index directly; on a fresh install Room first creates
         * the ordinary one from the entity, and [CREATE_PARTIAL_RESULT_INDEX]
         * replaces it. Validation only sees the name and columns, so the
         * `WHERE` clause is invisible to it.
         */
        const val PARTIAL_RESULT_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS `index_question_results_questionId_id` " +
                "ON `question_results` (`questionId`, `id`) " +
                "WHERE `selectedOptionIds` != ''"

        /**
         * Adds the bounded-Test attempt metadata, the three representative
         * counters and the Phase-1 indexes (sections 9.10, 12.1, 12.3).
         *
         * Every new attempt column defaults to its legacy value: old rows get
         * `testType = NULL`, `requestedCount = 0` and `samplingVersion = 1`,
         * so they are never comparable and never contribute to `rep*`. The
         * counters start at 0 with no backfill; they describe post-migration
         * representative attempts only, which is the intended scope.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `attempts` ADD COLUMN `testType` TEXT")
                db.execSQL(
                    "ALTER TABLE `attempts` ADD COLUMN `requestedCount` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `attempts` ADD COLUMN `scopeCategoryIds` " +
                        "TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `attempts` ADD COLUMN `scopeKey` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `attempts` ADD COLUMN `samplingVersion` " +
                        "INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE `attempts` ADD COLUMN `seed` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `attempts` ADD COLUMN `exclusionMode` " +
                        "TEXT NOT NULL DEFAULT 'NONE'"
                )
                db.execSQL(
                    "ALTER TABLE `attempts` ADD COLUMN `targetTopicIds` " +
                        "TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `attempts` ADD COLUMN `compositionJson` " +
                        "TEXT NOT NULL DEFAULT ''"
                )

                db.execSQL(
                    "ALTER TABLE `question_stats` ADD COLUMN `repCorrect` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `question_stats` ADD COLUMN `repWrong` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `question_stats` ADD COLUMN `repSkip` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_question_stats_paperId` " +
                        "ON `question_stats` (`paperId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_attempts_paperId_testType_requestedCount_scopeKey_" +
                        "samplingVersion_exclusionMode_finishedAt` " +
                        "ON `attempts` (`paperId`, `testType`, `requestedCount`, " +
                        "`scopeKey`, `samplingVersion`, `exclusionMode`, `finishedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_state_paperId_leech_dueAt` " +
                        "ON `card_state` (`paperId`, `leech`, `dueAt`)"
                )
                db.execSQL(PARTIAL_RESULT_INDEX_SQL)
            }
        }

        /**
         * Fresh installs get the raw partial index here, because no migration
         * runs for them. Room has already created the ordinary index declared
         * on [QuestionResultEntity] in `createAllTables`, so drop that one
         * first and replace it with the partial form. Kept next to
         * [MIGRATION_20_21], which executes the same statement for an upgrade.
         */
        /**
         * The exact-tag index (`question_tags`) and the custom-type variant
         * column on `attempts` (phase 3).
         *
         * `questions.tags` stays canonical for search and export; the new
         * table is a derived index, backfilled from the CSV through
         * [TagNormalizer] — the same split, trim, fold and dedupe every
         * writer uses, so the migration cannot disagree with live writes.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `question_tags` (" +
                        "`questionId` TEXT NOT NULL, `tag` TEXT NOT NULL, " +
                        "`tagFolded` TEXT NOT NULL, " +
                        "PRIMARY KEY(`questionId`, `tagFolded`), " +
                        "FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_question_tags_tagFolded_questionId` " +
                        "ON `question_tags` (`tagFolded`, `questionId`)"
                )
                db.execSQL("ALTER TABLE `attempts` ADD COLUMN `variant` TEXT")
                backfillQuestionTags(db)
            }
        }

        /**
         * Fixed panels (phase 3): a named, frozen set of question ids per
         * paper. The DDL mirrors [PanelEntity] and [PanelQuestionEntity]
         * exactly; a panel loses a member to the question cascade rather than
         * silently substituting another.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `panels` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`paperId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`paperId`) REFERENCES `papers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_panels_paperId_name` " +
                        "ON `panels` (`paperId`, `name`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `panel_questions` (" +
                        "`panelId` INTEGER NOT NULL, `questionId` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`panelId`, `questionId`), " +
                        "FOREIGN KEY(`panelId`) REFERENCES `panels`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_panel_questions_panelId_position` " +
                        "ON `panel_questions` (`panelId`, `position`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_panel_questions_questionId` " +
                        "ON `panel_questions` (`questionId`)"
                )
            }
        }

        /**
         * Stores the negative-marking rate each attempt applied. Older rows
         * stay NULL: the report keeps its historic count-based fallback for
         * them, because the rate cannot be recovered once the marks are gone.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `attempts` ADD COLUMN `negativeMarking` REAL")
            }
        }

        /**
         * Keys `question_tags` off the stored CSV in the same normalisation as
         * the runtime writers. Keyset-paged by rowid, one compiled insert per
         * batch — the backfill touches every tagged question, so peak memory
         * is one batch, not the whole bank.
         */
        private fun backfillQuestionTags(db: SupportSQLiteDatabase) {
            val insert = db.compileStatement(
                "INSERT OR IGNORE INTO `question_tags` " +
                    "(`questionId`, `tag`, `tagFolded`) VALUES (?, ?, ?)"
            )
            try {
                var last = -1L
                while (true) {
                    val batch = ArrayList<Triple<Long, String, String>>(FOLDED_BACKFILL_BATCH)
                    db.query(
                        "SELECT rowid, id, tags FROM questions " +
                            "WHERE rowid > ? ORDER BY rowid LIMIT $FOLDED_BACKFILL_BATCH",
                        arrayOf(last)
                    ).use { c ->
                        while (c.moveToNext()) {
                            batch += Triple(c.getLong(0), c.getString(1), c.getString(2))
                        }
                    }
                    if (batch.isEmpty()) break
                    for ((rowid, questionId, csv) in batch) {
                        for (label in TagNormalizer.fromCsv(csv)) {
                            insert.clearBindings()
                            insert.bindString(1, questionId)
                            insert.bindString(2, label.tag)
                            insert.bindString(3, label.folded)
                            insert.executeInsert()
                        }
                        last = rowid
                    }
                }
            } finally {
                insert.close()
            }
        }

        val CREATE_PARTIAL_RESULT_INDEX = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_question_results_questionId_id`")
                db.execSQL(PARTIAL_RESULT_INDEX_SQL)
            }
        }

        private const val FOLDED_BACKFILL_BATCH = 4000

        /**
         * Fills the folded columns from the flattened `searchText` already
         * stored (and the tags column). Keyset-paged by rowid — stable because
         * the update never changes the key — so peak memory is one batch, not
         * a whole 250k-question table, for the same reason as
         * [backfillSearchText]. Folded in Kotlin with [String.lowercase], not
         * SQL `lower()`: the whole point is folding beyond A–Z, and the
         * binders fold the query with the same call.
         */
        private fun backfillFolded(db: SupportSQLiteDatabase) {
            // One compiled statement per column, rebound per row — the same
            // shape the 14→15 backfill against ~5M rows proved out.
            fun fold(
                select: String,
                update: String,
                folded: (android.database.Cursor) -> List<String>
            ) {
                val statement = db.compileStatement(update)
                try {
                    var last = -1L
                    while (true) {
                        val batch = ArrayList<Pair<Long, List<String>>>(FOLDED_BACKFILL_BATCH)
                        db.query(
                            "$select WHERE rowid > ? ORDER BY rowid LIMIT $FOLDED_BACKFILL_BATCH",
                            arrayOf(last)
                        ).use { c ->
                            while (c.moveToNext()) batch += c.getLong(0) to folded(c)
                        }
                        if (batch.isEmpty()) break
                        for ((rowid, values) in batch) {
                            statement.clearBindings()
                            values.forEachIndexed { i, v -> statement.bindString(i + 1, v) }
                            statement.bindLong(values.size + 1, rowid)
                            statement.executeUpdateDelete()
                            last = rowid
                        }
                    }
                } finally {
                    statement.close()
                }
            }

            fold(
                "SELECT rowid, searchText, tags FROM questions",
                "UPDATE questions SET searchTextFolded = ?, tagsFolded = ? WHERE rowid = ?"
            ) { c -> listOf(c.getString(1).lowercase(), c.getString(2).lowercase()) }
            fold(
                "SELECT rowid, searchText FROM options",
                "UPDATE options SET searchTextFolded = ? WHERE rowid = ?"
            ) { c -> listOf(c.getString(1).lowercase()) }
            fold(
                "SELECT rowid, searchText FROM passages",
                "UPDATE passages SET searchTextFolded = ? WHERE rowid = ?"
            ) { c -> listOf(c.getString(1).lowercase()) }
        }

        /**
         * Adds `test_progress`: the in-progress test session's answers, one row
         * per question reached, so answering costs one small upsert instead of
         * re-serialising every answer so far into the resume snapshot. New and
         * empty — the answers of a session already running stay in the snapshot
         * it was written with, and are adopted into rows on resume.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `test_progress` (" +
                        "`paperId` TEXT NOT NULL, `questionId` TEXT NOT NULL, " +
                        "`selection` TEXT NOT NULL, `revealed` INTEGER NOT NULL, " +
                        "`flagged` INTEGER NOT NULL, `dwellSeconds` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`paperId`, `questionId`), " +
                        "FOREIGN KEY(`paperId`) REFERENCES `papers`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
            }
        }

        /**
         * Fills the `searchText` columns from the element JSON already stored.
         * Keyset-paged by rowid — stable because the update never changes the
         * key — so peak memory is one batch, not a whole 250k-question table.
         */
        private fun backfillSearchText(db: SupportSQLiteDatabase) {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

            fun flattened(raw: String) = raw.parseContentElements(json).searchableText

            // One compiled statement per table, rebound per row. The 14→15
            // upgrade touches every question, option and passage — ~5M rows on
            // a million-question library — and a fresh `execSQL` per row spent
            // most of the migration re-compiling the same UPDATE.
            fun backfill(
                table: String,
                select: String,
                value: (android.database.Cursor) -> String
            ) {
                val update = db.compileStatement(
                    "UPDATE $table SET searchText = ? WHERE rowid = ?"
                )
                try {
                    var last = -1L
                    while (true) {
                        val batch = ArrayList<Pair<Long, String>>(SEARCH_BACKFILL_BATCH)
                        db.query(
                            "$select WHERE rowid > ? ORDER BY rowid LIMIT $SEARCH_BACKFILL_BATCH",
                            arrayOf(last)
                        ).use { c ->
                            while (c.moveToNext()) batch += c.getLong(0) to value(c)
                        }
                        if (batch.isEmpty()) break
                        for ((rowid, text) in batch) {
                            update.clearBindings()
                            update.bindString(1, text)
                            update.bindLong(2, rowid)
                            update.executeUpdateDelete()
                            last = rowid
                        }
                    }
                } finally {
                    update.close()
                }
            }

            backfill("questions", "SELECT rowid, text FROM questions") { c ->
                flattened(c.getString(1))
            }
            backfill("options", "SELECT rowid, text FROM options") { c ->
                flattened(c.getString(1))
            }
            backfill("passages", "SELECT rowid, title, text FROM passages") { c ->
                passageSearchText(c.getString(1), c.getString(2).parseContentElements(json))
            }
        }

        /**
         * The production builder, without the singleton or the journal mode.
         * Kept separate so a fresh-create test builds the same schema and
         * callbacks as the app: a test that hand-added
         * [CREATE_PARTIAL_RESULT_INDEX] would stay green if [get] stopped
         * registering it, which is exactly the regression the two-path index
         * exists to prevent.
         */
        internal fun builder(
            context: Context,
            name: String = "mcq.db"
        ): RoomDatabase.Builder<AppDatabase> =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                    MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
                    MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
                    MIGRATION_22_23, MIGRATION_23_24
                )
                .addCallback(CREATE_PARTIAL_RESULT_INDEX)

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: builder(context)
                    // WAL keeps readers (the browsed list, an open test session,
                    // the search screen) non-blocking while an import or bulk
                    // write holds the write lock — the screens observe the same
                    // database an import is writing to. Backups and restores read
                    // and write through Room inside transactions, so they see
                    // consistent snapshots either way; nothing copies the raw
                    // file, which is the operation WAL would have broken.
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .build().also { INSTANCE = it }
            }
    }
}
