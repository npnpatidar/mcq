package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.toContentJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17
            )
            .allowMainThreadQueries()
            .build()

    /**
     * Builds a genuine v15 file by replaying the production 6->15 migrations,
     * with a category and a question seeded: a 15->16 test then proves both
     * that the indices land and that the data under them survives.
     */
    private fun createV15WithSeed() {
        createV6(
            insertPaper(),
            "INSERT INTO categories (id,paperId,title,parentId,sortOrder) " +
                "VALUES ('c1','p1','Cat',NULL,0)",
            "INSERT INTO questions (id,categoryId,text,image,explanation,explanationImage," +
                "difficulty,marks,tags,sortOrder,contentHash) VALUES " +
                "('q1','c1','Q one',NULL,'because',NULL,'medium',2.0,'tag',0,'hash1')"
        )
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val raw = helper.writableDatabase
            AppDatabase.MIGRATION_6_7.migrate(raw)
            AppDatabase.MIGRATION_7_8.migrate(raw)
            AppDatabase.MIGRATION_8_9.migrate(raw)
            AppDatabase.MIGRATION_9_10.migrate(raw)
            AppDatabase.MIGRATION_10_11.migrate(raw)
            AppDatabase.MIGRATION_11_12.migrate(raw)
            AppDatabase.MIGRATION_12_13.migrate(raw)
            AppDatabase.MIGRATION_13_14.migrate(raw)
            AppDatabase.MIGRATION_14_15.migrate(raw)
            raw.version = 15
        }
    }

    /**
     * Builds a genuine v9 file: the v6 schema plus the *real* 6->7, 7->8 and
     * 8->9 migrations, then a seeded SM-2 schedule. Replaying the production
     * [androidx.room.migration.Migration] objects instead of copying their DDL
     * keeps this fixture from going stale when those statements change.
     */
    private fun createV9WithSeed() {
        createV6(insertPaper())
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val raw = helper.writableDatabase
            AppDatabase.MIGRATION_6_7.migrate(raw)
            AppDatabase.MIGRATION_7_8.migrate(raw)
            AppDatabase.MIGRATION_8_9.migrate(raw)
            raw.execSQL(
                "INSERT INTO card_state (paperId,questionId,ease,intervalDays,dueAt," +
                    "reps,lapses,leech,lastReviewedAt,contentHash) " +
                    "VALUES ('p1','q1',2.5,3,1234,2,1,0,1000,'hash1')"
            )
            raw.version = 9
        }
    }

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
    fun `migration 7 to 8 drops orphan answer keys and cascades on delete`() {
        runBlocking {
            createV6(
                insertPaper(),
                "INSERT INTO categories (id,paperId,title,parentId,sortOrder) " +
                    "VALUES ('c1','p1','Cat',NULL,0)",
                "INSERT INTO questions (id,categoryId,text,image,explanation,explanationImage," +
                    "difficulty,marks,tags,sortOrder,contentHash) VALUES " +
                    "('q1','c1','Q one',NULL,'because',NULL,'medium',2.0,'tag',0,'hash1')",
                // One live key and one whose question is already gone.
                "INSERT INTO correct_answers (questionId,optionId) VALUES ('q1','q1-a'),('gone','x')"
            )
            val db = openMigrated()
            assertEquals(listOf("q1-a"), db.correctAnswerDao().getCorrectIds("q1"))
            assertTrue(db.correctAnswerDao().getCorrectIds("gone").isEmpty())
            // The new cascade removes the key with its question.
            db.questionDao().deleteById("q1")
            assertTrue(db.correctAnswerDao().getCorrectIds("q1").isEmpty())
            db.close()
        }
    }

    @Test
    fun `migration 8 to 9 adds passages table and nullable passageId`() {
        runBlocking {
            createV6(
                insertPaper(),
                "INSERT INTO categories (id,paperId,title,parentId,sortOrder) " +
                    "VALUES ('c1','p1','Cat',NULL,0)",
                "INSERT INTO questions (id,categoryId,text,image,explanation,explanationImage," +
                    "difficulty,marks,tags,sortOrder,contentHash) VALUES " +
                    "('q1','c1','Q one',NULL,'because',NULL,'medium',2.0,'tag',0,'hash1')"
            )
            val db = openMigrated()
            // Opening ran 6->7->8->9; Room validated the final schema (including
            // the new passages table and the questions.passageId column). An
            // additive migration leaves every existing row verbatim.
            assertEquals(0, db.questionDao().getById("q1")!!.sortOrder)
            assertTrue(db.questionDao().getById("q1")!!.passageId == null)
            assertTrue(db.passageDao().getAll().isEmpty())
            // A passage round-trips through the migrated schema.
            db.passageDao().upsert(
                com.mcqapp.data.local.PassageEntity(
                    id = "p1", categoryId = "c1", title = "T", text = "[]", searchText = "T",
                    image = null
                )
            )
            assertEquals("T", db.passageDao().getById("p1")!!.title)
            db.close()
        }
    }

    @Test
    fun `migration 9 to 10 wipes card_state and lands FSRS columns and review_log`() {
        runBlocking {
            createV9WithSeed()
            val db = openMigrated()
            // Opening ran 9->10 and Room validated the final schema.
            // The wipe: an SM-2 schedule must not be misread as FSRS memory.
            assertTrue(db.cardStateDao().getByPaper("p1").isEmpty())
            // The four new columns are live and round-trip.
            db.cardStateDao().upsert(
                sampleCard().copy(
                    difficulty = 6.5,
                    stability = 12.0,
                    cardState = com.mcqapp.domain.CardStateKind.REVIEW,
                    reviewCount = 4
                )
            )
            val stored = db.cardStateDao().get("p1", "q1")!!
            assertEquals(6.5, stored.difficulty, 0.0001)
            assertEquals(12.0, stored.stability, 0.0001)
            assertEquals(com.mcqapp.domain.CardStateKind.REVIEW, stored.cardState)
            assertEquals(4, stored.reviewCount)
            // review_log exists and accepts a graded review.
            db.reviewLogDao().insert(
                com.mcqapp.data.local.ReviewLogEntity(
                    paperId = "p1",
                    questionId = "q1",
                    rating = 3,
                    reviewedAt = 5000,
                    elapsedSeconds = 12,
                    stateBefore = "new",
                    stabilityAfter = 3.5,
                    difficultyAfter = 5.0,
                    intervalDaysAfter = 3
                )
            )
            val history = db.reviewLogDao().historyForQuestion("q1")
            assertEquals(1, history.size)
            assertEquals(3, history.first().rating)
            assertEquals(3.5, history.first().stabilityAfter!!, 0.0001)
            assertEquals(1, db.reviewLogDao().countByPaper("p1"))
            db.close()
        }
    }

    @Test
    fun `review_log rows are removed with their paper`() {
        runBlocking {
            createV9WithSeed()
            val db = openMigrated()
            db.reviewLogDao().insert(
                com.mcqapp.data.local.ReviewLogEntity(
                    paperId = "p1", questionId = "q1", rating = 1, reviewedAt = 100
                )
            )
            db.paperDao().deleteById("p1")
            assertTrue(db.reviewLogDao().historyForQuestion("q1").isEmpty())
            db.close()
        }
    }

    @Test
    fun `full chain 3 to 10 migrates cleanly and keeps data`() {
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
                    AppDatabase.MIGRATION_6_7,
                    AppDatabase.MIGRATION_7_8,
                    AppDatabase.MIGRATION_8_9,
                    AppDatabase.MIGRATION_9_10,
                    AppDatabase.MIGRATION_10_11,
                    AppDatabase.MIGRATION_11_12,
                    AppDatabase.MIGRATION_12_13,
                    AppDatabase.MIGRATION_13_14,
                    AppDatabase.MIGRATION_14_15,
                    AppDatabase.MIGRATION_15_16,
                    AppDatabase.MIGRATION_16_17
                )
                .allowMainThreadQueries()
                .build()
            // One open exercises 3->4->...->17; Room validates the final schema.
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

    @Test
    fun `migration 11 to 12 lands the aggregate and title indexes`() {
        runBlocking {
            createV6(insertPaper())
            val db = openMigrated()
            // Opening ran the whole chain to v12 and Room validated the schema.
            db.cardStateDao().getByPaper("p1")
            db.close()
            val check = android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath("migration-test.db").path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            fun indexExists(name: String): Boolean = check.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name=?",
                arrayOf(name)
            ).use { it.moveToFirst() }
            assertTrue("papers(title)", indexExists("index_papers_title"))
            assertTrue(
                "card_state(paperId,cardState,dueAt)",
                indexExists("index_card_state_paperId_cardState_dueAt")
            )
            assertTrue(
                "question_results(attemptId,isCorrect)",
                indexExists("index_question_results_attemptId_isCorrect")
            )
            assertTrue(
                "categories(paperId,title)",
                indexExists("index_categories_paperId_title")
            )
            // The composite replaces the standalone prefix.
            assertEquals(false, indexExists("index_question_results_attemptId"))
            assertEquals(false, indexExists("index_categories_paperId"))
            // The stale card_state dueAt index is dropped by the 13->14 rebuild.
            assertEquals(false, indexExists("index_card_state_dueAt"))
            check.close()
        }
    }

    @Test
    fun `migration 12 to 13 backfills question_stats from existing results`() {
        runBlocking {
            createV6(
                insertPaper(),
                "INSERT INTO attempts (id,paperId,title,totalQuestions,correctCount," +
                    "wrongCount,skippedCount,score,maxScore,durationSeconds,finishedAt) " +
                    "VALUES (1,'p1','Paper',3,1,1,0,1.0,2.0,60,2000)",
                "INSERT INTO question_results (id,attemptId,questionId,categoryTitle,text," +
                    "optionsJson,correctOptionIds,selectedOptionIds,isCorrect,explanation," +
                    "explanationImage,dwellSeconds) VALUES " +
                    "(1,1,'q1','Cat','Q1','[]','a','a',1,'',NULL,0)",
                "INSERT INTO question_results (id,attemptId,questionId,categoryTitle,text," +
                    "optionsJson,correctOptionIds,selectedOptionIds,isCorrect,explanation," +
                    "explanationImage,dwellSeconds) VALUES " +
                    "(2,1,'q1','Cat','Q1b','[]','a','b',0,'',NULL,0)",
                // Ungraded: no answer key, so it must not rank as hardest.
                "INSERT INTO question_results (id,attemptId,questionId,categoryTitle,text," +
                    "optionsJson,correctOptionIds,selectedOptionIds,isCorrect,explanation," +
                    "explanationImage,dwellSeconds) VALUES " +
                    "(3,1,'q2','Cat','Q2','[]','','x',0,'',NULL,0)"
            )
            val db = openMigrated()
            val hardest = db.questionStatsDao().getHardestQuestions(5)
            assertEquals(1, hardest.size)
            val q1 = hardest.first()
            assertEquals("q1", q1.questionId)
            assertEquals(2, q1.attempts)
            assertEquals(1, q1.correct)
            assertEquals(1, q1.wrong)
            // The last result row's text is what the aggregate carries.
            assertEquals("Q1b", q1.text)
            val weakest = db.questionStatsDao().getWeakestCategories(5)
            assertEquals(1, weakest.size)
            assertEquals("p1", weakest.first().paperId)
            assertEquals("Paper", weakest.first().paperTitle)
            assertEquals(1, weakest.first().correct)
            assertEquals(2, weakest.first().graded)
            db.close()
        }
    }

    /**
     * 14->15 adds the flattened `searchText` columns the prefilter scans.
     * Rows written before the upgrade carry only the elements JSON, so the
     * migration must derive the column from it; otherwise every question that
     * predates the upgrade would be stored with an empty string and silently
     * disappear from search.
     */
    @Test
    fun migration14To15BackfillsFlattenedSearchText() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val questionText = listOf(
            ContentElement.TextElement("Net <b>external</b> force"),
            // No space around the operator, so "v=u" stays searchable as typed.
            ContentElement.MathElement("<math><mi>v</mi><mo>=</mo><mi>u</mi></math>")
        ).toContentJson(json)
        val optionText = listOf(ContentElement.TextElement("m/s<sup>2</sup>")).toContentJson(json)

        v6Schema.forEach { dbFile.execSQL(it) }
        dbFile.execSQL(insertPaper())
        dbFile.execSQL(
            "INSERT INTO categories (id,paperId,title,parentId,sortOrder) " +
                "VALUES ('c1','p1','Cat',NULL,0)"
        )
        dbFile.execSQL(
            "INSERT INTO questions (id,categoryId,text,image,explanation,difficulty,marks,tags," +
                "sortOrder,contentHash) VALUES ('q1','c1',?,NULL,'why','medium',2.0,'net',0,'h1')",
            arrayOf(questionText)
        )
        dbFile.execSQL(
            "INSERT INTO options (questionId,id,text,image,sortOrder) " +
                "VALUES ('q1','o1',?,NULL,0)",
            arrayOf(optionText)
        )
        dbFile.close()

        val db = openMigrated()
        try {
            runBlocking {
                val question = db.questionDao().getById("q1")!!
                // Markup dropped, the formula kept as typed, elements joined
                // by a single space — exactly what the in-memory filter sees.
                assertEquals("Net external force v=u", question.searchText)
                assertEquals("m/s 2", db.optionDao().getByQuestion("q1").single().searchText)
            }
        } finally {
            db.close()
        }
    }

    /**
     * Builds a genuine v16 file by replaying the production 6->16 migrations,
     * with a paper and a question seeded: a 16->17 test then proves the new
     * table lands and that its rows hang off the paper.
     */
    private fun createV16WithSeed() {
        createV6(
            insertPaper(),
            "INSERT INTO categories (id,paperId,title,parentId,sortOrder) " +
                "VALUES ('c1','p1','Cat',NULL,0)",
            "INSERT INTO questions (id,categoryId,text,image,explanation,explanationImage," +
                "difficulty,marks,tags,sortOrder,contentHash) VALUES " +
                "('q1','c1','Q one',NULL,'because',NULL,'medium',2.0,'tag',0,'hash1')"
        )
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val raw = helper.writableDatabase
            AppDatabase.MIGRATION_6_7.migrate(raw)
            AppDatabase.MIGRATION_7_8.migrate(raw)
            AppDatabase.MIGRATION_8_9.migrate(raw)
            AppDatabase.MIGRATION_9_10.migrate(raw)
            AppDatabase.MIGRATION_10_11.migrate(raw)
            AppDatabase.MIGRATION_11_12.migrate(raw)
            AppDatabase.MIGRATION_12_13.migrate(raw)
            AppDatabase.MIGRATION_13_14.migrate(raw)
            AppDatabase.MIGRATION_14_15.migrate(raw)
            AppDatabase.MIGRATION_15_16.migrate(raw)
            raw.version = 16
        }
    }

    /**
     * 16->17 adds `test_progress`: the in-progress session's answers, one row per
     * question, so writing an answer no longer re-serialises every answer so far.
     */
    @Test
    fun `migration 16 to 17 adds the session answers table`() {
        runBlocking {
            createV16WithSeed()
            val db = openMigrated()
            try {
                // Opening ran 16->17; Room's identity check on open is what
                // proves the migrated schema matches the entity.
                db.testProgressDao().upsertAll(
                    listOf(
                        com.mcqapp.data.local.TestProgressEntity(
                            paperId = "p1",
                            questionId = "q1",
                            selection = "q1-a",
                            revealed = true,
                            flagged = true,
                            dwellSeconds = 7
                        )
                    )
                )
                val stored = db.testProgressDao().getForPaper("p1").single()
                assertEquals("q1-a", stored.selection)
                assertTrue(stored.revealed)
                assertEquals(7L, stored.dwellSeconds)
                // The answers belong to the paper: deleting it takes them with it.
                db.paperDao().deleteById("p1")
                assertTrue(db.testProgressDao().getForPaper("p1").isEmpty())
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun `migration 15 to 16 lands the browse order indexes`() {
        runBlocking {
            createV15WithSeed()
            val db = openMigrated()
            try {
                // Opening ran 15->16; Room's identity check on open is what
                // proves the migrated schema matches the entities.
                val repository = com.mcqapp.data.repository.McqRepository(db, context)
                assertEquals("Q one", repository.getQuestionsForPaper("p1").first().text)
            } finally {
                db.close()
            }
            val check = android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath("migration-test.db").path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            val sql = { name: String ->
                check.rawQuery(
                    "SELECT sql FROM sqlite_master WHERE type='index' AND name=?",
                    arrayOf(name)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }
            val categories = sql("index_categories_paperId_sortOrder_title")
            assertTrue("categories order index should exist: $categories",
                categories != null && categories.contains("sortOrder"))
            val questions = sql("index_questions_categoryId_sortOrder")
            assertTrue("questions order index should exist: $questions",
                questions != null && questions.contains("sortOrder"))
            assertNull(
                "the standalone categoryId index is subsumed by (categoryId, sortOrder)",
                sql("index_questions_categoryId")
            )
            check.close()
        }
    }
}
