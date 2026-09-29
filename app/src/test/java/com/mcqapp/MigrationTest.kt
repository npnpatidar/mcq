package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migrations are the one change that can destroy an existing install's data,
 * and they cannot be exercised by an in-memory database. Each test here
 * hand-builds the *previous* version's schema with raw SQL, runs the real
 * migration, and then opens the file with Room. Room's own identity-hash
 * check on open is the assertion: if the migrated schema does not match the
 * entities exactly, opening throws instead of silently corrupting data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: android.database.sqlite.SQLiteDatabase

    /** v6 schema: everything except card_state. */
    private val v6Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `papers` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`description` TEXT NOT NULL, `durationMinutes` INTEGER NOT NULL, " +
            "`negativeMarking` REAL NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `paperId` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, `parentId` TEXT, `sortOrder` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`), FOREIGN KEY(`paperId`) REFERENCES `papers`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_categories_paperId` ON `categories` (`paperId`)",
        "CREATE INDEX IF NOT EXISTS `index_categories_parentId` ON `categories` (`parentId`)",
        "CREATE TABLE IF NOT EXISTS `questions` (`id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, " +
            "`text` TEXT NOT NULL, `image` TEXT, `explanation` TEXT NOT NULL, " +
            "`explanationImage` TEXT, `difficulty` TEXT NOT NULL, `marks` REAL NOT NULL, " +
            "`tags` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, " +
            "PRIMARY KEY(`id`), FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_questions_categoryId` ON `questions` (`categoryId`)",
        "CREATE TABLE IF NOT EXISTS `options` (`questionId` TEXT NOT NULL, `id` TEXT NOT NULL, " +
            "`text` TEXT NOT NULL, `image` TEXT, `sortOrder` INTEGER NOT NULL, " +
            "PRIMARY KEY(`questionId`, `id`), FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_options_questionId` ON `options` (`questionId`)",
        "CREATE TABLE IF NOT EXISTS `correct_answers` (`questionId` TEXT NOT NULL, " +
            "`optionId` TEXT NOT NULL, PRIMARY KEY(`questionId`, `optionId`))",
        "CREATE INDEX IF NOT EXISTS `index_correct_answers_optionId` " +
            "ON `correct_answers` (`optionId`)",
        "CREATE TABLE IF NOT EXISTS `bookmarks` (`questionId` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`questionId`))",
        "CREATE TABLE IF NOT EXISTS `attempts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`paperId` TEXT NOT NULL, `title` TEXT NOT NULL, `totalQuestions` INTEGER NOT NULL, " +
            "`correctCount` INTEGER NOT NULL, `wrongCount` INTEGER NOT NULL, " +
            "`skippedCount` INTEGER NOT NULL, `score` REAL NOT NULL, `maxScore` REAL NOT NULL, " +
            "`durationSeconds` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `question_results` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `attemptId` INTEGER NOT NULL, " +
            "`questionId` TEXT NOT NULL, `categoryTitle` TEXT NOT NULL, `text` TEXT NOT NULL, " +
            "`optionsJson` TEXT NOT NULL, `correctOptionIds` TEXT NOT NULL, " +
            "`selectedOptionIds` TEXT NOT NULL, `isCorrect` INTEGER NOT NULL, " +
            "`explanation` TEXT NOT NULL, `explanationImage` TEXT, " +
            "`dwellSeconds` INTEGER NOT NULL, FOREIGN KEY(`attemptId`) REFERENCES `attempts`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_question_results_attemptId` " +
            "ON `question_results` (`attemptId`)",
        "CREATE TABLE IF NOT EXISTS room_master_table " +
            "(id INTEGER PRIMARY KEY,identity_hash TEXT)"
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("migration-test.db")
        dbFile = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath("migration-test.db"), null
        )
        dbFile.version = 6
    }

    @After
    fun teardown() {
        dbFile.close()
        context.deleteDatabase("migration-test.db")
    }

    /** Creates the v6 schema, then runs each seed statement verbatim. */
    private fun createV6(vararg seed: String) {
        v6Schema.forEach { dbFile.execSQL(it) }
        seed.forEach { dbFile.execSQL(it) }
        dbFile.close()
    }

    private fun insertPaper(duration: Int = 0, negative: String = "0.0", id: String = "p1") =
        "INSERT INTO papers (id,title,description,durationMinutes,negativeMarking,createdAt) " +
            "VALUES ('$id','Paper','',$duration,$negative,1000)"

    private fun sampleCard() = com.mcqapp.data.local.CardStateEntity(
        paperId = "p1",
        questionId = "q1",
        ease = 2.5,
        intervalDays = 3,
        dueAt = 1234L,
        reps = 2,
        lapses = 0,
        leech = false,
        lastReviewedAt = 1000L,
        contentHash = "hash1"
    )

    /** v3 schema: no explanationImage, no marks, no dwellSeconds, no card_state. */
    private val v3Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `papers` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`description` TEXT NOT NULL, `durationMinutes` INTEGER NOT NULL, " +
            "`negativeMarking` REAL NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL, `paperId` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, `parentId` TEXT, `sortOrder` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`), FOREIGN KEY(`paperId`) REFERENCES `papers`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_categories_paperId` ON `categories` (`paperId`)",
        "CREATE INDEX IF NOT EXISTS `index_categories_parentId` ON `categories` (`parentId`)",
        "CREATE TABLE IF NOT EXISTS `questions` (`id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, " +
            "`text` TEXT NOT NULL, `image` TEXT, `explanation` TEXT NOT NULL, " +
            "`difficulty` TEXT NOT NULL, `tags` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, " +
            "`contentHash` TEXT NOT NULL, PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_questions_categoryId` ON `questions` (`categoryId`)",
        "CREATE TABLE IF NOT EXISTS `options` (`questionId` TEXT NOT NULL, `id` TEXT NOT NULL, " +
            "`text` TEXT NOT NULL, `image` TEXT, `sortOrder` INTEGER NOT NULL, " +
            "PRIMARY KEY(`questionId`, `id`), FOREIGN KEY(`questionId`) REFERENCES `questions`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_options_questionId` ON `options` (`questionId`)",
        "CREATE TABLE IF NOT EXISTS `correct_answers` (`questionId` TEXT NOT NULL, " +
            "`optionId` TEXT NOT NULL, PRIMARY KEY(`questionId`, `optionId`))",
        "CREATE INDEX IF NOT EXISTS `index_correct_answers_optionId` " +
            "ON `correct_answers` (`optionId`)",
        "CREATE TABLE IF NOT EXISTS `bookmarks` (`questionId` TEXT NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`questionId`))",
        "CREATE TABLE IF NOT EXISTS `attempts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`paperId` TEXT NOT NULL, `title` TEXT NOT NULL, `totalQuestions` INTEGER NOT NULL, " +
            "`correctCount` INTEGER NOT NULL, `wrongCount` INTEGER NOT NULL, " +
            "`skippedCount` INTEGER NOT NULL, `score` REAL NOT NULL, `maxScore` REAL NOT NULL, " +
            "`durationSeconds` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `question_results` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `attemptId` INTEGER NOT NULL, " +
            "`questionId` TEXT NOT NULL, `categoryTitle` TEXT NOT NULL, `text` TEXT NOT NULL, " +
            "`optionsJson` TEXT NOT NULL, `correctOptionIds` TEXT NOT NULL, " +
            "`selectedOptionIds` TEXT NOT NULL, `isCorrect` INTEGER NOT NULL, " +
            "`explanation` TEXT NOT NULL, FOREIGN KEY(`attemptId`) REFERENCES `attempts`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_question_results_attemptId` " +
            "ON `question_results` (`attemptId`)",
        "CREATE TABLE IF NOT EXISTS room_master_table " +
            "(id INTEGER PRIMARY KEY,identity_hash TEXT)"
    )

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "migration-test.db")
            .addMigrations(
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7
            )
            .allowMainThreadQueries()
            .build()

    @Test
    fun `migration 6 to 7 creates the card_state table`() {
        runBlocking {
            createV6()
            val db = openMigrated()
            // Opening runs the migration; a schema mismatch throws here.
            db.cardStateDao().getByPaper("p1")
            db.close()
            val check = android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath("migration-test.db").path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            check.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='card_state'", null
            ).use { cursor ->
                assertTrue("card_state should exist after migration", cursor.moveToFirst())
            }
            check.close()
        }
    }

    @Test
    fun `migration 6 to 7 preserves existing papers and questions`() {
        runBlocking {
            createV6(
                insertPaper(duration = 10, negative = "0.25"),
                "INSERT INTO categories (id,paperId,title,parentId,sortOrder) " +
                    "VALUES ('c1','p1','Cat',NULL,0)",
                "INSERT INTO questions (id,categoryId,text,image,explanation,explanationImage," +
                    "difficulty,marks,tags,sortOrder,contentHash) VALUES " +
                    "('q1','c1','Q one',NULL,'because',NULL,'medium',2.0,'tag',0,'hash1')"
            )
            val db = openMigrated()
            val repository = com.mcqapp.data.repository.McqRepository(db, context)
            assertEquals("Paper", repository.getPaper("p1")!!.title)
            val questions = repository.getQuestionsForPaper("p1")
            assertEquals(1, questions.size)
            assertEquals("Q one", questions.first().text)
            assertEquals(2.0, questions.first().marks, 0.0001)
            db.close()
        }
    }

    @Test
    fun `migration 6 to 7 preserves attempts and results`() {
        runBlocking {
            createV6(
                insertPaper(),
                "INSERT INTO attempts (id,paperId,title,totalQuestions,correctCount,wrongCount," +
                    "skippedCount,score,maxScore,durationSeconds,finishedAt) " +
                    "VALUES (1,'p1','Paper',1,1,0,0,1.0,1.0,30,2000)",
                "INSERT INTO question_results (id,attemptId,questionId,categoryTitle,text," +
                    "optionsJson,correctOptionIds,selectedOptionIds,isCorrect,explanation," +
                    "explanationImage,dwellSeconds) VALUES " +
                    "(1,1,'q1','Cat','Q one','[]','q1-a','q1-a',1,'because',NULL,12)"
            )
            val db = openMigrated()
            val repository = com.mcqapp.data.repository.McqRepository(db, context)
            val attempt = repository.getAttempt(1L)!!
            assertEquals(1, attempt.correctCount)
            val results = repository.getAttemptResults(1L)
            assertEquals(1, results.size)
            assertEquals(12L, results.first().dwellSeconds)
            assertTrue(results.first().isCorrect)
            db.close()
        }
    }

    @Test
    fun `migration 6 to 7 preserves bookmarks`() {
        runBlocking {
            createV6(
                insertPaper(),
                "INSERT INTO bookmarks (questionId,createdAt) VALUES ('q1',500)"
            )
            val db = openMigrated()
            val repository = com.mcqapp.data.repository.McqRepository(db, context)
            assertTrue(repository.isBookmarked("q1"))
            db.close()
        }
    }

    @Test
    fun `card_state starts empty after migration`() {
        runBlocking {
            createV6(insertPaper())
            val db = openMigrated()
            assertTrue(db.cardStateDao().getByPaper("p1").isEmpty())
            db.close()
        }
    }

    @Test
    fun `card_state accepts and reads back a row after migration`() {
        runBlocking {
            createV6(insertPaper())
            val db = openMigrated()
            db.cardStateDao().upsert(sampleCard())
            val stored = db.cardStateDao().get("p1", "q1")!!
            assertEquals(3, stored.intervalDays)
            assertEquals(1234L, stored.dueAt)
            assertEquals(2, stored.reps)
            assertEquals("hash1", stored.contentHash)
            db.close()
        }
    }

    @Test
    fun `card_state is removed when its paper is deleted`() {
        runBlocking {
            createV6(insertPaper())
            val db = openMigrated()
            db.cardStateDao().upsert(sampleCard())
            db.paperDao().deleteById("p1")
            assertTrue(db.cardStateDao().getByPaper("p1").isEmpty())
            db.close()
        }
    }

    @Test
    fun `full chain 3 to 7 migrates cleanly and keeps data`() {
        runBlocking {
            context.deleteDatabase("chain-test.db")
            val chainFile = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath("chain-test.db"), null
            )
            chainFile.version = 3
            v3Schema.forEach { chainFile.execSQL(it) }
            chainFile.execSQL(
                "INSERT INTO papers (id,title,description,durationMinutes,negativeMarking,createdAt) " +
                    "VALUES ('p1','Paper','',10,0.25,1000)"
            )
            chainFile.execSQL(
                "INSERT INTO categories (id,paperId,title,parentId,sortOrder) " +
                    "VALUES ('c1','p1','Cat',NULL,0)"
            )
            chainFile.execSQL(
                "INSERT INTO questions (id,categoryId,text,image,explanation,difficulty,tags," +
                    "sortOrder,contentHash) VALUES " +
                    "('q1','c1','Q one',NULL,'because','medium','tag',0,'hash1')"
            )
            chainFile.close()

            val db = Room.databaseBuilder(context, AppDatabase::class.java, "chain-test.db")
                .addMigrations(
                    AppDatabase.MIGRATION_3_4,
                    AppDatabase.MIGRATION_4_5,
                    AppDatabase.MIGRATION_5_6,
                    AppDatabase.MIGRATION_6_7
                )
                .allowMainThreadQueries()
                .build()
            // One open exercises 3->4->5->6->7; Room validates the final schema.
            db.cardStateDao().getByPaper("p1")
            val repository = com.mcqapp.data.repository.McqRepository(db, context)
            assertEquals("Paper", repository.getPaper("p1")!!.title)
            val question = repository.getQuestionsForPaper("p1").first()
            assertEquals("Q one", question.text)
            // marks arrived with 4->5, explanationImage with 3->4.
            assertEquals(1.0, question.marks, 0.0001)
            db.close()
            context.deleteDatabase("chain-test.db")
        }
    }
}
