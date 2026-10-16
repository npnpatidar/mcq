package com.mcqapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PaperEntity::class,
        CategoryEntity::class,
        QuestionEntity::class,
        OptionEntity::class,
        CorrectAnswerEntity::class,
        BookmarkEntity::class,
        AttemptEntity::class,
        QuestionResultEntity::class,
        QuestionStatsEntity::class,
        CardStateEntity::class,
        ReviewLogEntity::class,
        PassageEntity::class
    ],
    version = 13,
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
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun attemptDao(): AttemptDao
    abstract fun questionStatsDao(): QuestionStatsDao
    abstract fun cardStateDao(): CardStateDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun passageDao(): PassageDao

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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mcq.db"
                ).addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13
                )
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
