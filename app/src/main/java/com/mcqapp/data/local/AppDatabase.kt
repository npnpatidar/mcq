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
        CardStateEntity::class,
        ReviewLogEntity::class,
        PassageEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun paperDao(): PaperDao
    abstract fun categoryDao(): CategoryDao
    abstract fun questionDao(): QuestionDao
    abstract fun optionDao(): OptionDao
    abstract fun correctAnswerDao(): CorrectAnswerDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun attemptDao(): AttemptDao
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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mcq.db"
                ).addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
                ).build().also { INSTANCE = it }
            }
    }
}
