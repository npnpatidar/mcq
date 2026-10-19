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
import com.mcqapp.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A question's content hash deliberately ignores the answer key, so a bank
 * that corrects an answer under a new id was silently reported as a
 * duplicate. The Settings toggle decides whether the stored answer is
 * refreshed; it defaults to off so existing behaviour is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateAnswersOnDuplicateTest {

    private lateinit var db: AppDatabase

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

    private fun file(questionId: String, correct: List<String>, image: String? = null) = McqFileDto(
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
                                image = image,
                                options = listOf(
                                    OptionDto(id = "a", text = "3"),
                                    OptionDto(id = "b", text = "4")
                                ),
                                correctOptionIds = correct
                            )
                        )
                    )
                )
            )
        )
    )

    /** A PNG larger than ImageDownscale's 1280 px cap, so import rewrites it. */
    private fun oversizedPngDataUri(): String {
        val bitmap = android.graphics.Bitmap.createBitmap(
            1400, 1400, android.graphics.Bitmap.Config.ARGB_8888
        )
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return com.mcqapp.data.io.ImageDownscale.encodeDataUri("image/png", out.toByteArray())
    }

    @Test
    fun theDefaultImporterLeavesTheCorrectionAlone() = kotlinx.coroutines.runBlocking {
        Importer(db).import(file("q1", listOf("b")))
        // No flag: the constructor default must keep today's behaviour.
        Importer(db).import(file("q2", listOf("a")))

        assertEquals(listOf("b"), db.correctAnswerDao().getCorrectIds("q1"))
        assertEquals(1, db.questionDao().countQuestions())
    }

    @Test
    fun withTheToggleOffTheCorrectionIsStillSkipped() = kotlinx.coroutines.runBlocking {
        Importer(db).import(file("q1", listOf("b")))
        val report = Importer(db, updateAnswersOnDuplicate = false).import(file("q2", listOf("a")))

        assertEquals(0, report.answersRefreshed)
        assertEquals(1, report.duplicateQuestions)
        assertEquals(1, db.questionDao().countQuestions())
        // The original answer is untouched.
        assertEquals(listOf("b"), db.correctAnswerDao().getCorrectIds("q1"))
    }

    @Test
    fun withTheToggleOnTheStoredAnswerIsRefreshed() = kotlinx.coroutines.runBlocking {
        Importer(db).import(file("q1", listOf("b")))
        val report = Importer(db, updateAnswersOnDuplicate = true).import(file("q2", listOf("a")))

        val q1 = db.questionDao().getById("q1")!!
        println("DEBUG differ=" + com.mcqapp.data.io.ContentHash.nonHashedFieldsDiffer(q1, db.correctAnswerDao().getCorrectIds("q1").toSet(), file("q2", listOf("a")).papers[0].categories[0].questions[0]))
        assertEquals(1, report.answersRefreshed)
        assertEquals(listOf("a"), db.correctAnswerDao().getCorrectIds("q1"))
        // Refreshed, not duplicated.
        assertEquals(1, db.questionDao().countQuestions())
    }

    @Test
    fun anUnchangedAnswerIsNotCountedAsARefresh() = kotlinx.coroutines.runBlocking {
        Importer(db).import(file("q1", listOf("b")))
        val report = Importer(db, updateAnswersOnDuplicate = true).import(file("q2", listOf("b")))

        assertEquals(0, report.answersRefreshed)
        assertEquals(1, db.questionDao().countQuestions())
    }

    @Test
    fun anOversizedImageDoesNotRefreshOnEveryReimport() = kotlinx.coroutines.runBlocking {
        val image = oversizedPngDataUri()
        Importer(db).import(file("q1", listOf("b"), image = image))
        // The row stores the downscaled picture, not the file's bytes: that
        // mismatch is what used to read as a permanent "non-hashed field".
        val stored = db.questionDao().getById("q1")!!
        assertNotEquals(image, stored.image)

        val report = Importer(db, updateAnswersOnDuplicate = true)
            .import(file("q1", listOf("b"), image = image))

        assertEquals(0, report.answersRefreshed)
        assertEquals(1, report.duplicateQuestions)
    }
}
