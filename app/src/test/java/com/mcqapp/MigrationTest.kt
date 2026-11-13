package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.BlueprintEntity
import com.mcqapp.data.local.BlueprintSectionEntity
import com.mcqapp.data.local.PanelEntity
import com.mcqapp.data.local.PanelQuestionEntity
import com.mcqapp.data.local.ReviewLogEntity
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

    /**
     * One list for every Room version bump, used by BOTH builders that open a
     * real file — a new migration added to only one of them used to leave the
     * other opening a pre-migration file against a schema it could never
     * reach ("a migration from 3 to 18 was required but not found").
     */
    private val ALL_MIGRATIONS = arrayOf(
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
        AppDatabase.MIGRATION_16_17,
        AppDatabase.MIGRATION_17_18,
        AppDatabase.MIGRATION_18_19,
        AppDatabase.MIGRATION_19_20,
        AppDatabase.MIGRATION_20_21,
        AppDatabase.MIGRATION_21_22,
        AppDatabase.MIGRATION_22_23,
        AppDatabase.MIGRATION_23_24,
        AppDatabase.MIGRATION_24_25,
        AppDatabase.MIGRATION_25_26,
        AppDatabase.MIGRATION_26_27
    )

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "migration-test.db")
            .addMigrations(*ALL_MIGRATIONS)
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

    /**
     * Builds a genuine v20 file by replaying the production 6->20 migrations,
     * then seeds one pre-v21 attempt and its aggregate. A 20->21 test then
     * proves the legacy defaults and the new indexes land without touching
     * the old row.
     */
    private fun createV20WithLegacyHistory() {
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
            AppDatabase.MIGRATION_9_10.migrate(raw)
            AppDatabase.MIGRATION_10_11.migrate(raw)
            AppDatabase.MIGRATION_11_12.migrate(raw)
            AppDatabase.MIGRATION_12_13.migrate(raw)
            AppDatabase.MIGRATION_13_14.migrate(raw)
            AppDatabase.MIGRATION_14_15.migrate(raw)
            AppDatabase.MIGRATION_15_16.migrate(raw)
            AppDatabase.MIGRATION_16_17.migrate(raw)
            AppDatabase.MIGRATION_17_18.migrate(raw)
            AppDatabase.MIGRATION_18_19.migrate(raw)
            AppDatabase.MIGRATION_19_20.migrate(raw)
            raw.execSQL(
                "INSERT INTO attempts (id,paperId,title,totalQuestions,correctCount," +
                    "wrongCount,skippedCount,score,maxScore,durationSeconds,finishedAt) " +
                    "VALUES (1,'p1','Paper',2,1,1,0,1.0,2.0,60,2000)"
            )
            raw.execSQL(
                "INSERT INTO question_stats (questionId,paperId,paperTitle,categoryTitle," +
                    "text,totalAttempts,gradedCount,correctCount,wrongCount,skipCount," +
                    "ungradedCount,lastResultIsCorrect,lastFinishedAt) " +
                    "VALUES ('q1','p1','Paper','Cat','Q1',2,2,1,1,0,0,1,2000)"
            )
            raw.version = 20
        }
    }

    /**
     * Builds a genuine v21 file (6->21) with a tagged question and a pre-v22
     * attempt, so 21->22 can be verified to backfill [question_tags] and add
     * the nullable [attempts.variant] column without dropping rows.
     */
    private fun createV21WithTaggedQuestion() {
        createV6(
            insertPaper(),
            "INSERT INTO categories (id,paperId,title,parentId,sortOrder) VALUES ('c1','p1','Cat',NULL,0)",
            "INSERT INTO questions (id,categoryId,text,image,explanation,explanationImage,difficulty,marks,tags,sortOrder,contentHash) " +
                "VALUES ('q1','c1','Q1',NULL,'',NULL,'MEDIUM',1.0,'Algebra, Fractions',0,'h1')"
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
            AppDatabase.MIGRATION_16_17.migrate(raw)
            AppDatabase.MIGRATION_17_18.migrate(raw)
            AppDatabase.MIGRATION_18_19.migrate(raw)
            AppDatabase.MIGRATION_19_20.migrate(raw)
            AppDatabase.MIGRATION_20_21.migrate(raw)
            raw.execSQL(
                "INSERT INTO attempts (id,paperId,title,totalQuestions,correctCount," +
                    "wrongCount,skippedCount,score,maxScore,durationSeconds,finishedAt," +
                    "testType,requestedCount,scopeCategoryIds,scopeKey,samplingVersion," +
                    "seed,exclusionMode,targetTopicIds,compositionJson) " +
                    "VALUES (1,'p1','Paper',1,1,0,0,1.0,1.0,60,2000,'MOCK',1,'','whole:p1',2,0,'NONE','','')"
            )
            raw.version = 21
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
                    searchTextFolded = "T".lowercase(),
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
                .addMigrations(*ALL_MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            // One open exercises 3->4->...->21; Room validates the final schema.
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
     * Builds a genuine v17 file: the v16 replay of 6->16, then the 16->17
     * migration, then search text written the way a pre-18 app stored it —
     * flattened but case-PRESERVING, with non-ASCII literals on hand — so a
     * 17->18 test can prove the folded columns are derived with full
     * Unicode, not SQL's A-Z `lower()`.
     */
    private fun createV17WithSeed() {
        createV16WithSeed()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val raw = helper.writableDatabase
            AppDatabase.MIGRATION_16_17.migrate(raw)
            raw.execSQL(
                "UPDATE questions SET searchText = 'CAFÉ central', tags = 'CAFÉ Tag' WHERE id = 'q1'"
            )
            // A real option row: createV16WithSeed seeds none, and the folded
            // assertion below reads it back through optionDao().getByQuestion
            // — an UPDATE against an empty set proved nothing.
            raw.execSQL(
                "INSERT INTO options (questionId,id,text,image,searchText,sortOrder) " +
                    "VALUES ('q1','o1','CAFÉ option',NULL,'CAFÉ option',0)"
            )
            raw.version = 17
        }
    }

    /**
     * 16->17 adds `test_progress`: the in-progress session's answers, one row per
     * question, so writing an answer no longer re-serialises every answer so far.
     */
    /**
     * 17->18 folds the search columns. SQL `LIKE` — and SQLite's own
     * `lower()` — case-fold A–Z only, so the prefilter vetoed a `café`
     * query against stored `CAFÉ` text before the in-memory authority (which
     * folds with full Unicode Kotlin) ever saw the row. The migration must
     * fold with Kotlin for the same reason the 14->15 flattening had to be
     * derived in code: SQL cannot express it.
     */
    @Test
    fun `migration 17 to 18 backfolds the search columns in full unicode`() {
        runBlocking {
            createV17WithSeed()
            val db = openMigrated()
            try {
                val question = db.questionDao().getById("q1")!!
                assertEquals("café central", question.searchTextFolded)
                assertEquals("café tag", question.tagsFolded)
                assertEquals(
                    "café option",
                    db.optionDao().getByQuestion("q1").single().searchTextFolded
                )
            } finally {
                db.close()
            }
        }
    }

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

    /**
     * Builds a genuine v18 file by replaying 6->18, then seeds one review-log
     * row. The row is what the 18->19 test must preserve: a migration that
     * rebuilt the table would silently drop history.
     */
    private fun createV18WithReview() {
        createV15WithSeed()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val raw = helper.writableDatabase
            AppDatabase.MIGRATION_15_16.migrate(raw)
            AppDatabase.MIGRATION_16_17.migrate(raw)
            AppDatabase.MIGRATION_17_18.migrate(raw)
            raw.execSQL(
                "INSERT INTO review_log (paperId,questionId,rating,reviewedAt," +
                    "elapsedSeconds,stateBefore,stabilityAfter,difficultyAfter," +
                    "intervalDaysAfter) VALUES ('p1','q1',3,2000,12,'REVIEW',5.0,5.0,3)"
            )
            raw.version = 18
        }
    }

    @Test
    fun `migration 18 to 19 adds nullable guess flags and keeps the log`() {
        runBlocking {
            createV18WithReview()
            val db = openMigrated()
            try {
                // Opening ran 18->19; Room's identity check on open is what
                // proves the migrated schema matches the entities.
                val old = db.reviewLogDao().getAll().single()
                assertEquals(3, old.rating)
                assertEquals(2000L, old.reviewedAt)
                // Pre-v19 history carries no flag: null, never false.
                assertNull(old.wasGuess)
                assertNull(old.wasCorrect)

                db.reviewLogDao().insert(
                    ReviewLogEntity(
                        paperId = "p1",
                        questionId = "q1",
                        rating = 1,
                        reviewedAt = 3000L,
                        wasGuess = true,
                        wasCorrect = false
                    )
                )
                val flagged = db.reviewLogDao().getAll().last()
                assertEquals(true, flagged.wasGuess)
                assertEquals(false, flagged.wasCorrect)
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

    @Test
    fun `migration 20 to 21 keeps legacy rows and creates the phase one indexes`() {
        runBlocking {
            createV20WithLegacyHistory()
            val db = openMigrated()
            try {
                // Opening ran 20->21; Room's identity check on open is what
                // proves the migrated schema matches the entities.
                val attempt = db.attemptDao().getAllAttempts().single()
                assertNull("a pre-v21 attempt has no type", attempt.testType)
                assertEquals(0, attempt.requestedCount)
                assertEquals("", attempt.scopeCategoryIds)
                assertEquals("", attempt.scopeKey)
                assertEquals(1, attempt.samplingVersion)
                assertEquals(0L, attempt.seed)
                assertEquals("NONE", attempt.exclusionMode)
                assertEquals("", attempt.targetTopicIds)
                assertEquals("", attempt.compositionJson)
                // The existing aggregate survives and starts with zero rep*.
                val stats = db.questionStatsDao().getHardestQuestions(5).single()
                assertEquals("q1", stats.questionId)
                assertEquals(2, stats.attempts)
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
            val partial = sql("index_question_results_questionId_id")
            assertTrue(
                "the graded-result partial index should exist: $partial",
                partial != null && partial.contains("selectedOptionIds") &&
                    partial.contains("WHERE")
            )
            assertTrue(sql("index_question_stats_paperId") != null)
            assertTrue(
                sql(
                    "index_attempts_paperId_testType_requestedCount_scopeKey_" +
                        "samplingVersion_exclusionMode_finishedAt"
                ) != null
            )
            assertTrue(sql("index_card_state_paperId_leech_dueAt") != null)
            check.rawQuery(
                "SELECT repCorrect,repWrong,repSkip FROM question_stats WHERE questionId='q1'",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
            }
            check.close()
        }
    }

    @Test
    fun `a fresh v21 create gets the partial index from the production builder`() {
        // Through AppDatabase.builder, not a hand-added callback: deleting the
        // callback from the production wiring must fail this test.
        context.deleteDatabase("fresh-create.db")
        val fresh = AppDatabase.builder(context, "fresh-create.db")
            .allowMainThreadQueries()
            .build()
        try {
            var found = false
            fresh.openHelper.writableDatabase
                .query("PRAGMA index_list('question_results')")
                .use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    val partial = cursor.getColumnIndexOrThrow("partial")
                    while (cursor.moveToNext()) {
                        if (cursor.getString(name) ==
                            "index_question_results_questionId_id"
                        ) {
                            found = true
                            assertEquals(1, cursor.getInt(partial))
                        }
                    }
                }
            assertTrue("the partial index must exist on a fresh create", found)
        } finally {
            fresh.close()
            context.deleteDatabase("fresh-create.db")
        }
    }

    @Test
    fun `migration 21 to 22 backfills question_tags and adds attempts variant`() {
        runBlocking {
            createV21WithTaggedQuestion()
            val db = openMigrated()
            try {
                val attempt = db.attemptDao().getAllAttempts().single()
                assertNull("a pre-v22 attempt has no variant", attempt.variant)
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
            assertTrue(sql("index_question_tags_tagFolded_questionId") != null)
            check.rawQuery("SELECT tagFolded FROM question_tags WHERE questionId='q1' ORDER BY tagFolded", null).use { cursor ->
                val got = mutableListOf<String>()
                while (cursor.moveToNext()) got += cursor.getString(0)
                assertEquals(listOf("algebra", "fractions"), got)
            }
            check.rawQuery("PRAGMA table_info(attempts)", null).use { cursor ->
                var hasVariant = false
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == "variant") hasVariant = true
                }
                assertTrue("attempts.variant should exist after 21->22", hasVariant)
            }
            check.close()
        }
    }

    @Test
    fun `migration 22 to 23 creates panels that shrink with a deleted question`() {
        runBlocking {
            createV21WithTaggedQuestion()
            // Bring the hand-built file to a genuine v22 first, so the test
            // exercises 22->23 alone rather than the whole chain.
            val config = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
            FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
                val raw = helper.writableDatabase
                AppDatabase.MIGRATION_21_22.migrate(raw)
                raw.version = 22
            }

            val db = openMigrated()
            try {
                val panelId = db.panelDao().insertPanel(
                    PanelEntity(paperId = "p1", name = "Half")
                )
                db.panelDao().insertQuestions(
                    listOf(PanelQuestionEntity(panelId, "q1", 0))
                )
                assertEquals(listOf("q1"), db.panelDao().questionIds(panelId))
                assertEquals(
                    1,
                    db.panelDao().panelsWithCountsForPaper("p1").single().questionCount
                )

                // A deleted question cascades out of the panel; the panel
                // shrinks rather than substituting a stranger.
                db.openHelper.writableDatabase.execSQL("DELETE FROM questions WHERE id='q1'")
                assertTrue(db.panelDao().questionIds(panelId).isEmpty())
                assertEquals(
                    0,
                    db.panelDao().panelsWithCountsForPaper("p1").single().questionCount
                )

                // Deleting the panel removes its rows too.
                db.panelDao().deletePanel(panelId)
                assertTrue(db.panelDao().panelsForPaper("p1").isEmpty())
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun `migration 24 to 25 creates blueprints with subtree cascades`() {
        runBlocking {
            createV21WithTaggedQuestion()
            // Bring to genuine v24 first.
            val config = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
            FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
                val raw = helper.writableDatabase
                AppDatabase.MIGRATION_21_22.migrate(raw)
                AppDatabase.MIGRATION_22_23.migrate(raw)
                AppDatabase.MIGRATION_23_24.migrate(raw)
                raw.version = 24
            }
            val db = openMigrated()
            try {
                // Opening ran 24->25; Room identity check proves the schema.
                val bpId = db.blueprintDao().insertBlueprint(
                    BlueprintEntity(paperId = "p1", name = "UPSC", createdAt = 100L)
                )
                val parentIds = db.blueprintDao().insertSections(
                    listOf(
                        BlueprintSectionEntity(
                            blueprintId = bpId,
                            categoryId = "c1",
                            parentSectionId = null,
                            parts = 2
                        )
                    )
                )
                assertEquals(1, parentIds.size)
                assertEquals(1, db.blueprintDao().sections(bpId).size)
                // Deleting the blueprint cascades all its sections.
                db.blueprintDao().delete(bpId)
                assertTrue(db.blueprintDao().sections(bpId).isEmpty())
                // Deleting a category cascades its section.
                val bp2 = db.blueprintDao().insertBlueprint(
                    BlueprintEntity(paperId = "p1", name = "SSC", createdAt = 200L)
                )
                db.blueprintDao().insertSections(
                    listOf(
                        BlueprintSectionEntity(
                            blueprintId = bp2,
                            categoryId = "c1",
                            parentSectionId = null,
                            parts = 1
                        )
                    )
                )
                db.categoryDao().deleteById("c1")
                assertTrue(db.blueprintDao().sections(bp2).isEmpty())
            } finally {
                db.close()
            }
        }
    }

    /**
     * A genuine v25 file: the production chain from the v21 seed through 25,
     * with one review already logged. The 25->27 chain must leave that row
     * intact, stamp it gradingVersion 0 / no provenance, and create the
     * session table with its cascade.
     */
    private fun createV25WithSeed() {
        createV21WithTaggedQuestion()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val raw = helper.writableDatabase
            AppDatabase.MIGRATION_21_22.migrate(raw)
            AppDatabase.MIGRATION_22_23.migrate(raw)
            AppDatabase.MIGRATION_23_24.migrate(raw)
            AppDatabase.MIGRATION_24_25.migrate(raw)
            raw.execSQL(
                "INSERT INTO review_log (id,paperId,questionId,rating,reviewedAt," +
                    "elapsedSeconds,wasGuess,wasCorrect) VALUES " +
                    "(1,'p1','q1',3,5000,12,0,1)"
            )
            raw.version = 25
        }
    }

    @Test
    fun `migration 25 to 27 keeps old reviews and versions the cuts`() {
        runBlocking {
            createV25WithSeed()
            val db = openMigrated()
            try {
                // Opening ran 25->27; Room's identity check proves the schema.
                val old = db.reviewLogDao().historyForQuestion("q1").single()
                assertEquals(3, old.rating)
                assertEquals(12L, old.elapsedSeconds)
                assertEquals(1, old.wasCorrect?.let { if (it) 1 else 0 })
                assertEquals(0, old.gradingVersion)
                assertNull(old.gradeSource)
                assertNull(old.proposedRating)
                assertNull(old.predictedRecall)
                assertNull(old.wasSkipped)

                // The version table is usable and the cascade is wired.
                val versionId = db.gradingRulesDao().insertVersion(
                    com.mcqapp.data.local.GradingVersionEntity(
                        easySeconds = 6,
                        slowSeconds = 60,
                        effectiveFrom = 100L,
                        reason = "prior"
                    )
                )
                assertEquals(versionId, db.gradingRulesDao().version(versionId)!!.id)
                db.studySessionDao().insert(
                    com.mcqapp.data.local.StudySessionEntity(
                        paperId = "p1",
                        startedAt = 100L,
                        endedAt = 200L,
                        activeSeconds = 100L
                    )
                )
                assertEquals(1, db.studySessionDao().getAll().size)
                // Deleting the paper cascades its session, so tidying the
                // library cannot leave orphan hours in the denominator.
                db.paperDao().deleteById("p1")
                assertTrue(db.studySessionDao().getAll().isEmpty())
            } finally {
                db.close()
            }
        }
    }

    /**
     * A genuine v26 file: the v25 chain plus the 25->26 columns, with the
     * ladder's two tables populated, so 26->27 has something to copy and
     * something to drop.
     */
    private fun createV26WithLadderHistory() {
        createV25WithSeed()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration-test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(26) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val raw = helper.writableDatabase
            AppDatabase.MIGRATION_25_26.migrate(raw)
            raw.execSQL(
                "INSERT INTO ladder_versions " +
                    "(id,easySeconds,slowSeconds,effectiveFrom,reason,previousId,blockId,policyVersion) " +
                    "VALUES (7,8,30,4000,'manual',NULL,NULL,1)"
            )
            raw.execSQL(
                "INSERT INTO ladder_blocks " +
                    "(id,startAt,endAt,ladderVersion,outcome) " +
                    "VALUES (1,1000,2000,7,'no-change')"
            )
            raw.version = 26
        }
    }

    @Test
    fun `migration 26 to 27 copies versions and drops the ladder blocks`() {
        runBlocking {
            createV26WithLadderHistory()
            val db = openMigrated()
            try {
                // The pair survives with its id, so every pre-v27 review row
                // still resolves the cuts that graded it.
                val version = db.gradingRulesDao().allVersions().single()
                assertEquals(7L, version.id)
                assertEquals(8L, version.easySeconds)
                assertEquals(30L, version.slowSeconds)
                assertEquals("manual", version.reason)

                // The evidence blocks are gone, table and all.
                val ladderTables = db.openHelper.readableDatabase.query(
                    "SELECT name FROM sqlite_master WHERE type='table' " +
                        "AND name IN ('ladder_versions','ladder_blocks')"
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                assertTrue("ladder tables still present: $ladderTables", ladderTables.isEmpty())

                // The old review keeps its version id; the column is intact.
                assertEquals(0, db.reviewLogDao().historyForQuestion("q1").single().gradingVersion)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun `full chain 23 to 25 restores a v23 file`() {
        runBlocking {
            createV21WithTaggedQuestion()
            val config = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration-test.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
            FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
                val raw = helper.writableDatabase
                AppDatabase.MIGRATION_21_22.migrate(raw)
                AppDatabase.MIGRATION_22_23.migrate(raw)
                raw.version = 23
            }
            val db = openMigrated()
            try {
                // Opening ran 23->24->25 and Room validated the final schema.
                assertTrue(db.blueprintDao().all().isEmpty())
                val id = db.blueprintDao().insertBlueprint(
                    BlueprintEntity(paperId = "p1", name = "State PSC", createdAt = 300L)
                )
                assertEquals("State PSC", db.blueprintDao().getById(id)?.name)
            } finally {
                db.close()
            }
        }
    }
}
