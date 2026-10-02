package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.AttemptDto
import com.mcqapp.data.io.AttemptResultDto
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
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
 * A result row's `optionsJson` comes from an imported backup and is stored
 * verbatim. Decoded strictly, a bad value threw — and because all three
 * callers swallow exceptions, one poisoned row left History and the mistake
 * badges permanently empty with no way to clear it from the UI.
 *
 * Options are written through the real `saveAttempt` path rather than
 * hand-written JSON, so the fixture cannot drift from the DTO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CorruptOptionsJsonTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        repository.ensurePaperAndCategory("p1", "Paper", "c1", "Cat")
        repository.saveQuestion(question("q1", "4"))
        repository.saveQuestion(question("q2", "5"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun question(id: String, answer: String) = Question(
        id = id,
        categoryId = "c1",
        text = "Q $id",
        options = listOf(
            QuestionOption("$id-a", answer),
            QuestionOption("$id-b", "wrong")
        ),
        correctOptionIds = setOf("$id-a"),
        marks = 1.0
    )

    /** Writes a real attempt through the production path. */
    private suspend fun saveTwoQuestionAttempt(): Long {
        val questions = repository.getQuestionsForPaper("p1")
        return repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a"), "q2" to setOf("q2-a")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 1_000L
        )
    }

    /** Simulates a backup that carried an unreadable optionsJson. */
    private fun poisonRow(questionId: String) {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE question_results SET optionsJson = 'x' WHERE questionId = ?",
            arrayOf<Any?>(questionId)
        )
    }

    @Test
    fun aRealOptionsJsonRoundTrips() = runBlocking {
        saveTwoQuestionAttempt()
        val results = repository.getAllQuestionResults()
        assertEquals(2, results.size)
        val first = results.first { it.questionId == "q1" }
        assertEquals(2, first.options.size)
        assertEquals("4", first.options.first { it.id == "q1-a" }.text)
    }

    @Test
    fun anUnreadableOptionsJsonDoesNotEmptyTheWholeHistory() = runBlocking {
        saveTwoQuestionAttempt()
        poisonRow("q2")

        val results = repository.getAllQuestionResults()
        // Before the guard this read threw, every caller swallowed it, and both
        // rows vanished from History.
        assertEquals(2, results.size)
        val healthy = results.single { it.questionId == "q1" }
        assertEquals(2, healthy.options.size)
        val broken = results.single { it.questionId == "q2" }
        assertTrue("the bad row degrades to no options", broken.options.isEmpty())
        // The rest of the row survives, so the attempt is still reviewable.
        assertEquals("Q q2", broken.text)
    }

    @Test
    fun mistakesStillWorkWithAPoisonedRow() = runBlocking {
        // saveAttempt records q2 wrong; poison its options and the mistake list
        // must still be derivable.
        val questions = repository.getQuestionsForPaper("p1")
        repository.saveAttempt(
            paperId = "p1",
            paperTitle = "Paper",
            questions = questions,
            selections = mapOf("q1" to setOf("q1-a"), "q2" to setOf("q2-b")),
            negativeMarking = 0.0,
            durationSeconds = 30,
            finishedAt = 1_000L
        )
        poisonRow("q1")
        assertEquals(listOf("q2"), repository.getMistakenQuestions("p1").map { it.id })
    }

    private fun backup(optionsJson: String) = McqFileDto(
        version = 1,
        papers = listOf(
            PaperDto(
                id = "p1",
                title = "Paper",
                categories = listOf(
                    CategoryDto(
                        id = "c1",
                        title = "Cat",
                        questions = listOf(QuestionDto(id = "q1", text = "Q q1"))
                    )
                )
            )
        ),
        attempts = listOf(
            AttemptDto(
                paperId = "p1",
                title = "Paper",
                results = listOf(
                    AttemptResultDto(
                        questionId = "q1",
                        categoryTitle = "Cat",
                        text = "Q q1",
                        optionsJson = optionsJson
                    )
                )
            )
        )
    )

    @Test
    fun importReplacesAnUnusableOptionsJsonRatherThanStoringIt() = runBlocking {
        Importer(db).import(backup("x"))

        val stored = db.attemptDao().getAllResults().single().optionsJson
        assertTrue("stored optionsJson was: $stored", stored.trim().startsWith("["))
        // And reading it back must not throw.
        assertEquals(1, repository.getAllQuestionResults().size)
    }

    @Test
    fun importKeepsAUsableOptionsJsonVerbatim() = runBlocking {
        val original = """[{"id":"a","text":"4","elements":[]}]"""
        Importer(db).import(backup(original))

        // No re-encoding drift for a value that is already fine.
        assertEquals(original, db.attemptDao().getAllResults().single().optionsJson)
        assertEquals(1, repository.getAllQuestionResults().single().options.size)
    }
}
