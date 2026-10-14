package com.mcqapp

import com.mcqapp.data.backup.BackupRestorer
import com.mcqapp.data.backup.BackupSource
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.AttemptDto
import com.mcqapp.data.io.AttemptResultDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.local.ReviewLogEntity
import androidx.room.Room
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The backup's whole promise: export, wipe, restore — and every row in
 * every table is the row it was. Content goes through the importer (ids,
 * marks, difficulty, tags, correct answers), study rows are written back
 * verbatim (per-paper schedules with their content hashes, the review
 * history with its ids), so the restore must be a fixpoint: a second
 * backup of the restored library describes the same state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreRoundTripTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun content() = McqFileDto(
        version = 1,
        papers = listOf(
            PaperDto(
                id = "paper-1",
                title = "Backup Paper",
                description = "a paper with everything",
                durationMinutes = 45,
                negativeMarking = 0.5,
                categories = listOf(
                    CategoryDto(
                        id = "cat-root",
                        title = "Root",
                        questions = listOf(
                            QuestionDto(
                                id = "q-1",
                                text = "What is <strong>2+2</strong>?",
                                marks = 2.5,
                                difficulty = "hard",
                                tags = listOf("arithmetic", "tricky"),
                                options = listOf(
                                    OptionDto(id = "a", text = "Three"),
                                    OptionDto(id = "b", text = "Four")
                                ),
                                correctOptionIds = listOf("b"),
                                explanation = "Because four."
                            ),
                            QuestionDto(
                                id = "q-2",
                                text = "Plain one",
                                options = listOf(
                                    OptionDto(id = "a", text = "Yes"),
                                    OptionDto(id = "b", text = "No")
                                ),
                                correctOptionIds = listOf("a")
                            )
                        )
                    ),
                    CategoryDto(
                        id = "cat-sub",
                        title = "Sub",
                        parentId = "cat-root",
                        questions = listOf(
                            QuestionDto(
                                id = "q-3",
                                text = "Nested category question",
                                options = listOf(
                                    OptionDto(id = "a", text = "1"),
                                    OptionDto(id = "b", text = "2")
                                ),
                                correctOptionIds = listOf("b")
                            )
                        )
                    )
                )
            )
        ),
        bookmarks = listOf("q-2"),
        attempts = listOf(
            AttemptDto(
                paperId = "paper-1",
                title = "Backup Paper",
                totalQuestions = 2,
                correctCount = 1,
                wrongCount = 1,
                skippedCount = 0,
                score = 2.5,
                maxScore = 3.5,
                durationSeconds = 120,
                finishedAt = 1_700_000_000_000,
                results = listOf(
                    AttemptResultDto(
                        questionId = "q-1",
                        categoryTitle = "Root",
                        text = "What is 2+2?",
                        optionsJson = "[]",
                        correctOptionIds = "b",
                        selectedOptionIds = "a",
                        isCorrect = false,
                        explanation = "Because four.",
                        dwellSeconds = 12
                    )
                )
            )
        ),
        passages = listOf(
            PassageDto(
                id = "passage-1",
                title = "A passage",
                categoryId = "cat-root",
                elements = listOf(com.mcqapp.domain.ContentElement.TextElement("Shared context."))
            )
        )
    )

    private suspend fun seed() {
        com.mcqapp.data.io.Importer(db).import(content())
        db.cardStateDao().upsertAll(
            listOf(
                CardStateEntity(
                    paperId = "paper-1",
                    questionId = "q-1",
                    ease = 2.31,
                    intervalDays = 9,
                    dueAt = 1_800_000_000_000,
                    reps = 7,
                    lapses = 1,
                    leech = false,
                    lastReviewedAt = 1_750_000_000_000,
                    contentHash = "hash-q-1",
                    difficulty = 5.5,
                    stability = 21.0,
                    cardState = com.mcqapp.domain.CardStateKind.REVIEW,
                    reviewCount = 7
                ),
                CardStateEntity(
                    paperId = "paper-1",
                    questionId = "q-2",
                    ease = 2.5,
                    intervalDays = 0,
                    dueAt = 0,
                    reps = 0,
                    lapses = 0,
                    leech = true,
                    lastReviewedAt = 0,
                    contentHash = "hash-q-2",
                    cardState = com.mcqapp.domain.CardStateKind.NEW
                )
            )
        )
        db.reviewLogDao().insertAll(
            listOf(
                ReviewLogEntity(
                    id = 0,
                    paperId = "paper-1",
                    questionId = "q-1",
                    rating = 3,
                    reviewedAt = 1_750_000_000_000,
                    elapsedSeconds = 15,
                    stateBefore = "REVIEW",
                    stabilityAfter = 21.0,
                    difficultyAfter = 5.5,
                    intervalDaysAfter = 9
                )
            )
        )
    }

    private suspend fun buildBackup(settings: McqBackupFormat.SettingsFile = McqBackupFormat.SettingsFile()): ByteArray {
        val source = BackupSource(db)
        return McqBackupFormat.write(
            manifest = source.manifest("test-1.0"),
            content = source.content(),
            study = source.study(inProgressTest = null),
            settings = settings
        )
    }

    private suspend fun allQuestionIds(): List<String> = db.questionDao().getAll().map { it.id }

    private suspend fun rowDump(): Map<String, List<String>> = mapOf(
        "papers" to db.paperDao().getAll().map { it.toString() }.sorted(),
        "categories" to db.categoryDao().getByPaper("paper-1").map { it.toString() }.sorted(),
        "questions" to db.questionDao().getAll().map { it.toString() }.sorted(),
        "options" to allQuestionIds().flatMap { db.optionDao().getForQuestions(listOf(it)) }
            .map { it.toString() }.sorted(),
        "correct" to allQuestionIds().flatMap { db.correctAnswerDao().getForQuestions(listOf(it)) }
            .map { it.toString() }.sorted(),
        "card_state" to db.cardStateDao().getAll().map { it.toString() }.sorted(),
        "review_log" to db.reviewLogDao().getAll().map { it.toString() }.sorted(),
        "bookmarks" to db.bookmarkDao().getAll().sorted(),
        "attempts" to db.attemptDao().getAllAttempts().map { it.copy(id = 0).toString() }.sorted(),
        "results" to db.attemptDao().getAllResults().map { it.copy(id = 0, attemptId = 0).toString() }.sorted(),
        "passages" to db.passageDao().getAll().map { it.toString() }.sorted()
    )

    @Test
    fun restoreReproducesEveryRow() = kotlinx.coroutines.runBlocking {
        seed()
        val before = rowDump()
        val backup = buildBackup()

        val report = BackupRestorer(db).restore(McqBackupFormat.read(backup))
        assertTrue(report.contains("1 papers"))
        assertTrue(report.contains("3 questions"))

        val after = rowDump()
        for ((table, rows) in before) {
            assertEquals("table $table must restore identically", rows, after[table])
        }
    }

    @Test
    fun aBackupOfARestoredLibraryDescribesTheSameState() = kotlinx.coroutines.runBlocking {
        seed()
        val backup = buildBackup()
        BackupRestorer(db).restore(McqBackupFormat.read(backup))
        val second = buildBackup()
        val reread = McqBackupFormat.read(second)
        assertEquals(
            McqBackupFormat.read(backup).content.papers,
            reread.content.papers
        )
        assertEquals(
            McqBackupFormat.read(backup).study.cardState,
            reread.study.cardState
        )
        assertEquals(
            McqBackupFormat.read(backup).study.reviewLog,
            reread.study.reviewLog
        )
    }

    @Test
    fun manifestCarriesCounts() = kotlinx.coroutines.runBlocking {
        seed()
        val manifest = McqBackupFormat.read(buildBackup()).manifest
        assertEquals("mcqapp-backup", manifest.format)
        assertEquals(1, manifest.papers)
        assertEquals(3, manifest.questions)
        assertEquals(1, manifest.attempts)
        assertEquals(1, manifest.reviews)
        assertEquals("test-1.0", manifest.appVersion)
        assertEquals(db.openHelper.readableDatabase.version, manifest.dbVersion)
    }

    @Test
    fun aForeignOrTruncatedFileIsRefusedWithAMeaningfulMessage() {
        try {
            McqBackupFormat.read("not a zip".toByteArray())
            throw AssertionError("expected Invalid")
        } catch (e: McqBackupFormat.Invalid) {
            assertTrue(e.message!!.contains("zip"))
        }
        val entries = mapOf(
            McqBackupFormat.MANIFEST to
                """{"format":"other-app","version":1}""".toByteArray()
        )
        val zip = java.io.ByteArrayOutputStream().let { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                for ((name, bytes) in entries) {
                    z.putNextEntry(java.util.zip.ZipEntry(name))
                    z.write(bytes)
                    z.closeEntry()
                }
            }
            out.toByteArray()
        }
        try {
            McqBackupFormat.read(zip)
            throw AssertionError("expected Invalid")
        } catch (e: McqBackupFormat.Invalid) {
            assertTrue(e.message!!.contains("not an mcqapp backup", ignoreCase = true))
        }
    }

    @Test
    fun restoreRefusesNothingWhenTheLibraryIsEmpty() = kotlinx.coroutines.runBlocking {
        val backup = McqBackupFormat.read(buildBackup())
        val report = BackupRestorer(db).restore(backup)
        assertTrue(report.contains("0 papers"))
        assertNull(McqBackupFormat.read(buildBackup()).study.inProgressTest)
        assertNotNull(backup)
    }
}
