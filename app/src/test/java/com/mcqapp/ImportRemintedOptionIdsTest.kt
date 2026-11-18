package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.io.StreamingImporter
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ContentElement
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The duplicate-refresh path keeps the stored option rows but rewrites the
 * answer key. A regenerated bank can give identical options fresh ids, so the
 * key must be corrected by option *position* and written back with the stored
 * ids — otherwise the question imports cleanly with a key that references no
 * option and can never be graded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportRemintedOptionIdsTest {

    private lateinit var db: AppDatabase
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun file(
        questionId: String,
        optionIds: List<String>,
        correct: List<String>,
        optionTexts: List<String> = listOf("3", "4"),
        extraOnFirstOption: List<ContentElement> = emptyList()
    ): McqFileDto {
        val options = optionIds.mapIndexed { index, id ->
            OptionDto(
                id = id,
                text = optionTexts[index],
                elements = if (index == 0 && extraOnFirstOption.isNotEmpty()) {
                    listOf(ContentElement.TextElement(optionTexts[0])) + extraOnFirstOption
                } else {
                    emptyList()
                }
            )
        }
        return McqFileDto(
            version = 1,
            papers = listOf(
                PaperDto(
                    id = "p1", title = "Paper",
                    categories = listOf(
                        CategoryDto(
                            id = "c1", title = "Cat",
                            questions = listOf(
                                QuestionDto(
                                    id = questionId,
                                    text = "2 + 2?",
                                    options = options,
                                    correctOptionIds = correct
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    private fun source(file: McqFileDto): () -> InputStream = {
        ByteArrayInputStream(json.encodeToString(McqFileDto.serializer(), file).toByteArray())
    }

    private fun storedOptionIds(questionId: String): List<String> = runBlocking {
        db.optionDao().getByQuestion(questionId).map { it.id }
    }

    @Test
    fun wholeFileRefreshRemapsCorrectedKeyOntoStoredOptionIds() = runBlocking {
        Importer(db).import(file("q1", listOf("a", "b"), listOf("b")))
        // Same question and option texts, fresh ids o0/o1, and the key moved
        // from the second option to the first.
        val report = Importer(db, updateAnswersOnDuplicate = true)
            .import(file("q2", listOf("o0", "o1"), listOf("o0")))

        assertEquals(1, report.answersRefreshed)
        assertEquals(1, db.questionDao().countQuestions())
        assertEquals(listOf("a", "b"), storedOptionIds("q1"))
        val correct = db.correctAnswerDao().getCorrectIds("q1")
        assertEquals(listOf("a"), correct)
        assertTrue(
            "the refreshed key must resolve against the stored option rows",
            correct.all { it in storedOptionIds("q1") }
        )
    }

    @Test
    fun streamingRefreshRemapsCorrectedKeyOntoStoredOptionIds() = runBlocking {
        Importer(db).import(file("q1", listOf("a", "b"), listOf("b")))
        val report = StreamingImporter(db, updateAnswersOnDuplicate = true, batchSize = 1)
            .import(source(file("q2", listOf("o0", "o1"), listOf("o0"))))

        assertEquals(1, report.answersRefreshed)
        assertEquals(1, db.questionDao().countQuestions())
        assertEquals(listOf("a", "b"), storedOptionIds("q1"))
        val correct = db.correctAnswerDao().getCorrectIds("q1")
        assertEquals(listOf("a"), correct)
        assertTrue(
            "the refreshed key must resolve against the stored option rows",
            correct.all { it in storedOptionIds("q1") }
        )
    }

    @Test
    fun remintedIdsWithAnUnchangedKeyAreNotARefresh() = runBlocking {
        Importer(db).import(file("q1", listOf("a", "b"), listOf("b")))
        // o1 sits at the same position b did: the key is unchanged, only the
        // option ids were re-minted. A raw-id comparison refreshed this and
        // wrote a dangling "o1" key.
        val report = Importer(db, updateAnswersOnDuplicate = true)
            .import(file("q2", listOf("o0", "o1"), listOf("o1")))

        assertEquals(0, report.answersRefreshed)
        assertEquals(1, report.duplicateQuestions)
        assertEquals(1, db.questionDao().countQuestions())
        assertEquals(listOf("b"), db.correctAnswerDao().getCorrectIds("q1"))
    }

    @Test
    fun sameIdsStillRefreshExactlyAsBefore() = runBlocking {
        Importer(db).import(file("q1", listOf("a", "b"), listOf("b")))
        val report = Importer(db, updateAnswersOnDuplicate = true)
            .import(file("q2", listOf("a", "b"), listOf("a")))

        assertEquals(1, report.answersRefreshed)
        assertEquals(listOf("a", "b"), storedOptionIds("q1"))
        assertEquals(listOf("a"), db.correctAnswerDao().getCorrectIds("q1"))
    }

    @Test
    fun streamingWithSameIdsStillRefreshesExactlyAsBefore() = runBlocking {
        Importer(db).import(file("q1", listOf("a", "b"), listOf("b")))
        val report = StreamingImporter(db, updateAnswersOnDuplicate = true, batchSize = 1)
            .import(source(file("q2", listOf("a", "b"), listOf("a"))))

        assertEquals(1, report.answersRefreshed)
        assertEquals(listOf("a"), db.correctAnswerDao().getCorrectIds("q1"))
    }

    @Test
    fun differentOptionContentWithTheSameHashIsNeverRemapped() = runBlocking {
        Importer(db).import(file("q1", listOf("a", "b"), listOf("b")))
        // A table in option 0 leaves textContent — and so the content hash —
        // unchanged, but it is genuinely different content. It must not be
        // treated as a refresh and have its key written onto q1.
        val incoming = file(
            "q2", listOf("o0", "o1"), listOf("o0"),
            extraOnFirstOption = listOf(
                ContentElement.TableElement(listOf(listOf("hidden")))
            )
        )
        val report = Importer(db, updateAnswersOnDuplicate = true).import(incoming)

        assertEquals(0, report.answersRefreshed)
        assertEquals(2, db.questionDao().countQuestions())
        assertEquals(listOf("b"), db.correctAnswerDao().getCorrectIds("q1"))
        assertEquals(listOf("o0"), db.correctAnswerDao().getCorrectIds("q2"))
    }

    @Test
    fun streamingDoesNotRefreshDifferentOptionContent() = runBlocking {
        Importer(db).import(file("q1", listOf("a", "b"), listOf("b")))
        val incoming = file(
            "q2", listOf("o0", "o1"), listOf("o0"),
            extraOnFirstOption = listOf(
                ContentElement.TableElement(listOf(listOf("hidden")))
            )
        )
        val report = StreamingImporter(db, updateAnswersOnDuplicate = true, batchSize = 1)
            .import(source(incoming))

        assertEquals(0, report.answersRefreshed)
        assertEquals(2, db.questionDao().countQuestions())
        assertEquals(listOf("b"), db.correctAnswerDao().getCorrectIds("q1"))
    }
}
