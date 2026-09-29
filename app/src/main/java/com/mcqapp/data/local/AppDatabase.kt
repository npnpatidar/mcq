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
        CardStateEntity::class
    ],
    version = 7,
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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mcq.db"
                ).addMigrations(
                    MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
                ).build().also { INSTANCE = it }
            }
    }
}
