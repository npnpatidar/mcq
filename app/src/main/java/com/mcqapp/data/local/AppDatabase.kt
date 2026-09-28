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
        QuestionResultEntity::class
    ],
    version = 4,
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

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN explanationImage TEXT")
                db.execSQL("ALTER TABLE question_results ADD COLUMN explanationImage TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mcq.db"
                ).addMigrations(MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
