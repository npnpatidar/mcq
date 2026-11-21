package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.backup.BackupRestorer
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.ContentHash
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.StreamingImporter
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CardStateEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.CardStateKind
import com.mcqapp.domain.ContentElement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * A one-option question is a flashcard whose answer was written as the only
 * option (a legacy bank, an old backup). Every import path — whole-file JSON,
 * streamed JSON, restore — has to normalise it before hashing or writing, so
 * the stored row, its duplicate hash and the searchable back all describe the
 * card the learner studies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlashcardImportTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun teardown() {
        db.close()
    }

    private val answerBack = listOf(
        ContentElement.TextElement("Paris"),
        ContentElement.TextElement("It is the capital.")
    )

    /** The raw old shape: the answer sits in a single option. */
    private fun oneOptionQuestion() = QuestionDto(
        id = "q1",
        text = "Capital of France?",
        options = listOf(OptionDto("a", "Paris")),
        correctOptionIds = listOf("a"),
        explanation = "It is the capital.",
        explanationElements = listOf(ContentElement.TextElement("It is the capital."))
    )

    /** The normalised shape the importer is expected to write. */
    private fun expectedFlashcard() = QuestionDto(
        id = "q1",
        text = "Capital of France?",
        options = emptyList(),
        correctOptionIds = emptyList(),
        explanation = "ParisIt is the capital.",
        explanationElements = answerBack
    )

    private fun oneOptionFile() = McqFileDto(
        version = 1,
        papers = listOf(
            PaperDto(
                id = "p1",
                title = "Deck",
                categories = listOf(
                    CategoryDto(id = "c1", title = "Cat", questions = listOf(oneOptionQuestion()))
                )
            )
        )
    )

    private val oneOptionJson = """
        {"version":1,"papers":[{"id":"p1","title":"Deck","categories":[{
          "id":"c1","title":"Cat","questions":[{
            "id":"q1",
            "question_elements":[{"type":"text","content":"Capital of France?"}],
            "options_elements":{"a":[{"type":"text","content":"Paris"}]},
            "correctOptionIds":["a"],
            "explanation_elements":[{"type":"text","content":"It is the capital."}]
          }]
        }]}]}
    """.trimIndent()

    private suspend fun assertStoredFlashcard() {
        val stored = repository.getQuestion("q1")!!
        assertTrue("the option became the back, not a choice", stored.options.isEmpty())
        assertTrue("a flashcard has no answer key", stored.correctOptionIds.isEmpty())
        assertEquals(answerBack, stored.explanationElements)
        assertEquals("ParisIt is the capital.", stored.explanation)

        val row = db.questionDao().getById("q1")!!
        assertEquals("Paris It is the capital.", row.backText)
        assertEquals("paris it is the capital.", row.backTextFolded)

        // The hash must be the one the normalised flashcard produces: a
        // re-import of the same file has to deduplicate against this row, and
        // the study layer recomputes the same value from it.
        assertEquals(ContentHash.of(expectedFlashcard()), row.contentHash)
        assertNotEquals(
            "the raw one-option hash must not be what the row stores",
            ContentHash.of(oneOptionQuestion()),
            row.contentHash
        )
    }

    @Test
    fun theWholeFileImporterConvertsAOneOptionQuestion() = runBlocking {
        val report = Importer(db).import(oneOptionFile())

        assertEquals(1, report.newQuestions)
        assertEquals("the conversion must reach the completion report", 1, report.recallConverted)
        assertStoredFlashcard()
    }

    @Test
    fun theStreamingImporterConvertsAOneOptionQuestion() = runBlocking {
        val report = StreamingImporter(db).import({ ByteArrayInputStream(oneOptionJson.toByteArray()) })

        assertEquals("the conversion must reach the completion report", 1, report.recallConverted)
        assertStoredFlashcard()
    }

    // ---- id-less one-option file: identity is the prompt -------------------

    private fun idLessBank(answer: String) = """
        {"version":1,"papers":[{"id":"p1","title":"Deck","categories":[{
          "id":"c1","title":"Cat","questions":[{
            "text":"Capital of France?",
            "options_elements":{"a":[{"type":"text","content":"$answer"}]},
            "correctOptionIds":["a"],
            "explanation_elements":[{"type":"text","content":"Capital city."}]
          }]
        }]}]}
    """.trimIndent()

    /**
     * A changed back on the same prompt is an answer refresh, not a new
     * question.
     *
     * An id-less one-option source is normalised to a prompt-only id, so the
     * re-import with another answer matches the stored card *and* its
     * prompt-only content hash. That takes the answers-refresh path (enabled by
     * `updateAnswersOnDuplicate`): the back is rewritten in place and the
     * schedule it earned is reset, because the learner studied a different
     * answer. The refresh returns before the duplicate counter, so it reports
     * `answersRefreshed = 1` rather than also counting a skipped duplicate.
     */
    @Test
    fun anIdLessOneOptionQuestionKeepsItsPromptIdentityWhenTheAnswerChanges() = runBlocking {
        val prompt = "Capital of France?"
        val expectedId = LegacyParser.stableQuestionId(prompt, emptyList())
        // The parser minted its id with the option in it; normalisation must
        // re-derive the prompt-only identity.
        assertNotEquals(
            "the fixture must exercise the option-inclusive parser id",
            expectedId,
            LegacyParser.parse(idLessBank("Paris"))
                .papers.single().categories.single().questions.single().id
        )

        Importer(db).import(LegacyParser.parse(idLessBank("Paris")))
        val stored = db.questionDao().getById(expectedId)
        assertNotNull("the card must be stored under its prompt-only id", stored)
        assertEquals(expectedId, stored!!.id)

        db.cardStateDao().upsert(
            CardStateEntity(
                paperId = "p1",
                questionId = expectedId,
                ease = 2.5,
                intervalDays = 4,
                dueAt = 1_700_000_000_000L,
                reps = 2,
                lapses = 0,
                leech = false,
                lastReviewedAt = 1_699_000_000_000L,
                contentHash = stored.contentHash,
                difficulty = 5.0,
                stability = 4.0,
                stabilityFast = 3.0,
                cardState = CardStateKind.REVIEW,
                reviewCount = 2
            )
        )

        val report = Importer(db, updateAnswersOnDuplicate = true)
            .import(LegacyParser.parse(idLessBank("Lyon")))

        // One prompt = one answer: the row is refreshed, not minted as a
        // second card and not skipped as an unchanged duplicate.
        assertEquals(0, report.newQuestions)
        assertEquals(0, report.updatedQuestions)
        assertEquals(0, report.duplicateQuestions)
        assertEquals(1, report.answersRefreshed)
        assertEquals(1, db.questionDao().countQuestions())
        val updated = repository.getQuestion(expectedId)!!
        assertTrue(updated.options.isEmpty())
        assertEquals(listOf(ContentElement.TextElement("Lyon"), ContentElement.TextElement("Capital city.")), updated.explanationElements)
        // The learner studied a different answer: the schedule resets.
        assertNull(db.cardStateDao().get("p1", expectedId))
    }

    @Test
    fun aRestoredOneOptionBackupComesBackAsAFlashcard() = runBlocking {
        // An old backup DTO still carries the answer as the only option; the
        // restore goes through the whole-file importer, which must normalise
        // it exactly as a fresh JSON import does.
        val backup = McqBackupFormat.Backup(
            manifest = McqBackupFormat.Manifest(dbVersion = db.openHelper.readableDatabase.version),
            content = oneOptionFile(),
            study = McqBackupFormat.StudyFile(),
            settings = McqBackupFormat.SettingsFile()
        )

        BackupRestorer(db).restore(backup)

        assertStoredFlashcard()
    }

    // ---- pre-29 backup: the schedule must follow the rewritten hash ---------

    private val oldOneOptionHash = ContentHash.of(oneOptionQuestion())

    private fun pre29Study() = McqBackupFormat.StudyFile(
        cardState = listOf(
            McqBackupFormat.CardStateRow(
                paperId = "p1",
                questionId = "q1",
                ease = 2.7,
                intervalDays = 17,
                dueAt = 1_700_000_000_000L,
                reps = 5,
                lapses = 1,
                leech = false,
                lastReviewedAt = 1_699_000_000_000L,
                // The hash the v28 database stored: the option was part of the
                // question's identity before the flashcard conversion.
                contentHash = oldOneOptionHash,
                difficulty = 6.5,
                stability = 11.25,
                stabilityFast = 9.5,
                cardState = "REVIEW",
                reviewCount = 5
            )
        ),
        reviewLog = listOf(
            McqBackupFormat.ReviewLogRow(
                id = 42L,
                paperId = "p1",
                questionId = "q1",
                rating = 3,
                reviewedAt = 1_699_000_000_000L,
                stateBefore = "REVIEW",
                stabilityAfter = 11.25,
                difficultyAfter = 6.5,
                stabilityFastAfter = 9.5,
                intervalDaysAfter = 17
            )
        )
    )

    private fun pre29Backup() = McqBackupFormat.Backup(
        manifest = McqBackupFormat.Manifest(dbVersion = 28),
        content = oneOptionFile(),
        study = pre29Study(),
        settings = McqBackupFormat.SettingsFile()
    )

    /** The restored card is intact and carries the flashcard's current hash. */
    private suspend fun assertPre29ScheduleRestored() {
        val question = repository.getQuestion("q1")!!
        assertTrue("the one-option question must be a card", question.options.isEmpty())
        assertTrue(db.optionDao().getByQuestion("q1").isEmpty())

        val row = db.questionDao().getById("q1")!!
        assertNotEquals(
            "the fixture must exercise a rewritten hash",
            oldOneOptionHash,
            row.contentHash
        )

        val card = db.cardStateDao().get("p1", "q1")
        assertNotNull("the restored schedule must exist", card)
        card!!
        assertEquals("the schedule must follow the question's hash", row.contentHash, card.contentHash)
        assertEquals(2.7, card.ease, 0.0)
        assertEquals(17, card.intervalDays)
        assertEquals(1_700_000_000_000L, card.dueAt)
        assertEquals(5, card.reps)
        assertEquals(1, card.lapses)
        assertEquals(1_699_000_000_000L, card.lastReviewedAt)
        assertEquals(6.5, card.difficulty, 0.0)
        assertEquals(11.25, card.stability, 0.0)
        assertEquals(9.5, card.stabilityFast, 0.0)
        assertEquals(5, card.reviewCount)
        assertEquals("REVIEW", card.cardState.name)

        val review = db.reviewLogDao().getAll().single()
        assertEquals(42L, review.id)
        assertEquals("q1", review.questionId)
        assertEquals(3, review.rating)
        assertEquals(11.25, review.stabilityAfter!!, 0.0)
    }

    @Test
    fun aPre29BackupKeepsItsScheduleWhenRestoredWholeFile() = runBlocking {
        BackupRestorer(db).restore(pre29Backup())

        assertPre29ScheduleRestored()
        // The study pull's stale sweep reads a hash mismatch as edited content
        // and deletes the card. Reconciled, the schedule must survive it.
        repository.getStudyQueue("p1")
        assertNotNull("the stale sweep must not delete the card", db.cardStateDao().get("p1", "q1"))
    }

    @Test
    fun aPre29BackupKeepsItsScheduleWhenRestoredByStream() = runBlocking {
        val bytes = McqBackupFormat.write(
            manifest = pre29Backup().manifest,
            content = oneOptionFile(),
            study = pre29Study(),
            settings = McqBackupFormat.SettingsFile()
        )

        BackupRestorer(db).restoreStreaming(ByteArrayInputStream(bytes))

        assertPre29ScheduleRestored()
        repository.getStudyQueue("p1")
        assertNotNull("the stale sweep must not delete the card", db.cardStateDao().get("p1", "q1"))
    }

    // ---- pre-29 id-less one-option row: restore must keep its id ----------

    private val promptOnlyId = LegacyParser.stableQuestionId("Capital of France?", emptyList())
    private val generatedOldId = LegacyParser.stableQuestionId(
        "Capital of France?",
        listOf(OptionDto("a", "Paris"))
    )

    private fun generatedIdQuestion() = QuestionDto(
        id = generatedOldId,
        text = "Capital of France?",
        options = listOf(OptionDto("a", "Paris")),
        correctOptionIds = listOf("a"),
        explanation = "It is the capital.",
        explanationElements = listOf(ContentElement.TextElement("It is the capital."))
    )

    private fun generatedIdFile() = McqFileDto(
        version = 1,
        papers = listOf(
            PaperDto(
                id = "p1",
                title = "Deck",
                categories = listOf(
                    CategoryDto(id = "c1", title = "Cat", questions = listOf(generatedIdQuestion()))
                )
            )
        ),
        bookmarks = listOf(generatedOldId)
    )

    private fun generatedIdStudy() = McqBackupFormat.StudyFile(
        cardState = listOf(
            McqBackupFormat.CardStateRow(
                paperId = "p1",
                questionId = generatedOldId,
                ease = 2.4,
                intervalDays = 12,
                dueAt = 1_700_000_000_000L,
                reps = 4,
                lapses = 1,
                leech = false,
                lastReviewedAt = 1_699_000_000_000L,
                // The v28 identity: the option was part of the hash.
                contentHash = ContentHash.of(generatedIdQuestion()),
                difficulty = 5.5,
                stability = 8.5,
                stabilityFast = 7.5,
                cardState = "REVIEW",
                reviewCount = 4
            )
        ),
        reviewLog = listOf(
            McqBackupFormat.ReviewLogRow(
                id = 77L,
                paperId = "p1",
                questionId = generatedOldId,
                rating = 3,
                reviewedAt = 1_699_000_000_000L,
                stateBefore = "REVIEW",
                stabilityAfter = 8.5,
                difficultyAfter = 5.5,
                stabilityFastAfter = 7.5,
                intervalDaysAfter = 12
            )
        )
    )

    private fun generatedIdBackup() = McqBackupFormat.Backup(
        manifest = McqBackupFormat.Manifest(dbVersion = 28),
        content = generatedIdFile(),
        study = generatedIdStudy(),
        settings = McqBackupFormat.SettingsFile()
    )

    /** Restore kept every id the archive's study rows reference. */
    private suspend fun assertGeneratedIdRestored() {
        assertNull(
            "restore must not mint the prompt-only id",
            db.questionDao().getById(promptOnlyId)
        )
        val row = db.questionDao().getById(generatedOldId)
        assertNotNull("the stored id must survive the restore", row)
        row!!
        assertTrue("the one-option row must be a card", db.optionDao().getByQuestion(generatedOldId).isEmpty())
        assertTrue(repository.getQuestion(generatedOldId)!!.options.isEmpty())

        val card = db.cardStateDao().get("p1", generatedOldId)
        assertNotNull("the schedule must follow the kept id", card)
        assertEquals("the schedule must follow the question's hash", row.contentHash, card!!.contentHash)
        assertEquals(2.4, card.ease, 0.0)
        assertEquals(4, card.reps)
        assertEquals(8.5, card.stability, 0.0)
        assertEquals(7.5, card.stabilityFast, 0.0)
        assertEquals("REVIEW", card.cardState.name)

        val review = db.reviewLogDao().getAll().single()
        assertEquals(77L, review.id)
        assertEquals(generatedOldId, review.questionId)
        assertTrue("the bookmark must follow the kept id", db.bookmarkDao().isBookmarked(generatedOldId))
    }

    @Test
    fun aPre29GeneratedIdSurvivesTheWholeFileRestore() = runBlocking {
        BackupRestorer(db).restore(generatedIdBackup())

        assertGeneratedIdRestored()
        repository.getStudyQueue("p1")
        assertNotNull(
            "the stale sweep must not delete the card",
            db.cardStateDao().get("p1", generatedOldId)
        )
    }

    @Test
    fun aPre29GeneratedIdSurvivesTheStreamedRestore() = runBlocking {
        val bytes = McqBackupFormat.write(
            manifest = generatedIdBackup().manifest,
            content = generatedIdFile(),
            study = generatedIdStudy(),
            settings = McqBackupFormat.SettingsFile()
        )

        BackupRestorer(db).restoreStreaming(ByteArrayInputStream(bytes))

        assertGeneratedIdRestored()
        repository.getStudyQueue("p1")
        assertNotNull(
            "the stale sweep must not delete the card",
            db.cardStateDao().get("p1", generatedOldId)
        )
    }

    @Test
    fun aZeroOptionFlashcardIsNotTreatedAsAOneOptionQuestion() = runBlocking {
        // The flashcard path must stay a no-op: an exported zero-option card
        // already carries its back and must not gain an option or lose it.
        val flashcard = QuestionDto(
            id = "q2",
            text = "Capital of Japan?",
            options = emptyList(),
            correctOptionIds = emptyList(),
            explanation = "Tokyo",
            explanationElements = listOf(ContentElement.TextElement("Tokyo"))
        )
        val file = McqFileDto(
            version = 1,
            papers = listOf(
                PaperDto(
                    id = "p1",
                    title = "Deck",
                    categories = listOf(CategoryDto(id = "c1", title = "Cat", questions = listOf(flashcard)))
                )
            )
        )

        val report = Importer(db).import(file)

        assertEquals("an already-card question still counts as an imported card", 1, report.recallConverted)
        val stored = repository.getQuestion("q2")!!
        assertTrue(stored.options.isEmpty())
        assertEquals(listOf(ContentElement.TextElement("Tokyo")), stored.explanationElements)
    }
}
