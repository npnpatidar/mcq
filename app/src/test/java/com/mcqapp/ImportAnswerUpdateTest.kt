package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.withDownscaledImages
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The duplicate hash deliberately ignores the answer key (and explanation,
 * marks, difficulty, tags), so a re-imported file that fixes only those must
 * update the stored question rather than being skipped as a duplicate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportAnswerUpdateTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun file(correct: List<String>, explanation: String = "Arithmetic.") = McqFileDto(
        version = 1,
        papers = listOf(
            PaperDto(
                id = "p1",
                title = "Deck",
                categories = listOf(
                    CategoryDto(
                        id = "c1",
                        title = "Cat",
                        questions = listOf(
                            QuestionDto(
                                id = "q1",
                                text = "2 + 2?",
                                options = listOf(OptionDto("a", "4"), OptionDto("b", "five")),
                                correctOptionIds = correct,
                                explanation = explanation
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun anAnswerOnlyCorrectionUpdatesInsteadOfSkipping() = runBlocking {
        Importer(db).import(file(listOf("a")))

        val report = Importer(db).import(file(listOf("b")))

        assertEquals(0, report.newQuestions)
        assertEquals(1, report.updatedQuestions)
        assertEquals(0, report.duplicateQuestions)
        assertEquals(listOf("b"), db.correctAnswerDao().getCorrectIds("q1"))
    }

    @Test
    fun anIdenticalReimportStillSkipsAsDuplicate() = runBlocking {
        Importer(db).import(file(listOf("a")))

        val report = Importer(db).import(file(listOf("a")))

        assertEquals(0, report.newQuestions)
        assertEquals(0, report.updatedQuestions)
        assertEquals(1, report.duplicateQuestions)
    }

    @Test
    fun anExplanationOnlyCorrectionUpdatesInsteadOfSkipping() = runBlocking {
        Importer(db).import(file(listOf("a"), explanation = "Arithmetic."))

        val report = Importer(db).import(file(listOf("a"), explanation = "Two plus two."))

        assertEquals(1, report.updatedQuestions)
        assertEquals(0, report.duplicateQuestions)
        assertEquals("Two plus two.", db.questionDao().getById("q1")!!.explanation)
    }

    @Test
    fun scaledTwinMirrorsTheFileStructure() {
        val png = "data:image/png;base64,iVBORw0KGgo="
        val f = McqFileDto(
            version = 1,
            papers = listOf(
                PaperDto(
                    id = "p1",
                    title = "Deck",
                    questions = listOf(
                        QuestionDto(
                            id = "q0",
                            text = "Top level?",
                            options = listOf(OptionDto("a", "1")),
                            correctOptionIds = listOf("a"),
                            image = png
                        )
                    ),
                    categories = listOf(
                        CategoryDto(
                            id = "c1",
                            title = "Cat",
                            questions = listOf(
                                QuestionDto(
                                    id = "q1",
                                    text = "2 + 2?",
                                    options = listOf(OptionDto("a", "4"), OptionDto("b", "five")),
                                    correctOptionIds = listOf("a"),
                                    image = png,
                                    explanationImage = png
                                )
                            )
                        )
                    )
                )
            )
        )

        val scaled = f.withDownscaledImages()

        // Robolectric cannot decode bitmaps, so downscale passes every image
        // through untouched and the twin equals the original — what this pins
        // is the mirror structure (ids, order, categories, top-level list).
        assertEquals(f, scaled)
        assertEquals(listOf("q0"), scaled.papers[0].questions.map { it.id })
        assertEquals(listOf("q1"), scaled.papers[0].categories[0].questions.map { it.id })
        assertEquals(png, scaled.papers[0].categories[0].questions[0].explanationImage)
    }

    @Test
    fun importStoresImagesFromTheScaledTwin() = runBlocking {
        val png = "data:image/png;base64,iVBORw0KGgo="
        val f = McqFileDto(
            version = 1,
            papers = listOf(
                PaperDto(
                    id = "p1",
                    title = "Deck",
                    categories = listOf(
                        CategoryDto(
                            id = "c1",
                            title = "Cat",
                            questions = listOf(
                                QuestionDto(
                                    id = "q1",
                                    text = "2 + 2?",
                                    options = listOf(OptionDto("a", "4", image = png), OptionDto("b", "five")),
                                    correctOptionIds = listOf("a"),
                                    image = png,
                                    explanationImage = png
                                )
                            )
                        )
                    )
                )
            )
        )

        Importer(db).import(f)

        // The stored rows carry the twin's images (pass-through here).
        val stored = db.questionDao().getById("q1")!!
        assertEquals(png, stored.image)
        assertEquals(png, stored.explanationImage)
        val optionA = db.optionDao().getByQuestion("q1").first { it.id == "a" }
        assertEquals(png, optionA.image)
    }
}
