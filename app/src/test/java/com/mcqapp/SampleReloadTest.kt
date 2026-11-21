package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.repository.McqRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bundled sample can be loaded a second time from the library's overflow
 * menu, and that reload must repair the stored rows: "Load sample paper" used
 * to exist only on the empty-library screen, so a fix in the asset never
 * reached a library that already had the sample.
 *
 * The load path is deliberately the one `LibraryViewModel.loadSampleData()`
 * runs — the bundled asset through [LegacyParser] into [Importer], with the
 * same duplicate-answers setting — so a menu-only change cannot pass while
 * the reload stays a no-op.
 *
 * Robolectric needs a real SQLite driver, so this runs on x86_64 CI only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleReloadTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The same parse/import calls `LibraryViewModel.loadSampleData()` makes; returns the paper id. */
    private suspend fun loadSample(): String {
        // The repo's other sample-asset tests read the source file directly;
        // Robolectric's merged asset manager is not where they look.
        val text = java.io.File(SAMPLE_ASSET).readText()
        val file = LegacyParser.parse(text.byteInputStream())
        Importer(db, repository.updateAnswersOnDuplicate().first()).import(file)
        return file.papers.first().id
    }

    private fun JsonElement.questionById(id: String): JsonObject? = when (this) {
        is JsonObject -> if ((get("id") as? JsonPrimitive)?.content == id) {
            this
        } else {
            values.firstNotNullOfOrNull { it.questionById(id) }
        }
        is JsonArray -> firstNotNullOfOrNull { it.questionById(id) }
        else -> null
    }

    /**
     * The option ids the asset itself gives [questionId] — its
     * `options_elements` keys. Derived so editing the sample (an extra option,
     * say) cannot break this test with a stale magic number.
     */
    private fun assetOptionIds(questionId: String): Set<String> {
        val root = Json.parseToJsonElement(java.io.File(SAMPLE_ASSET).readText())
        val question = root.questionById(questionId)
            ?: error("$questionId is not in the bundled sample asset")
        val options = question["options_elements"] as? JsonObject
            ?: error("$questionId has no options_elements in the asset")
        return options.keys
    }

    @Test
    fun reloadingTheSampleRestoresATamperedOptionAndKeepsTheKey() = runBlocking {
        val paperId = loadSample()
        val questionId = "q-vis-4"
        val expectedOptionIds = assetOptionIds(questionId)
        val before = db.optionDao().getByQuestion(questionId)
        assertEquals(
            "the asset stores its ${expectedOptionIds.size} labelled options " +
                "(${expectedOptionIds.sorted()})",
            expectedOptionIds,
            before.map { it.id }.toSet()
        )
        assertTrue(
            "option b carries its text label beside the picture",
            before.single { it.id == "b" }.searchText.contains("Square")
        )
        assertEquals(setOf("b"), db.correctAnswerDao().getCorrectIds(questionId).toSet())
        val questionsBefore = db.questionDao().countForPaper(paperId)

        // Tamper: the option rows (and their labels) are gone, so the question
        // is the picture-only stem the owner reported.
        db.optionDao().deleteByQuestion(questionId)
        assertTrue(db.optionDao().getByQuestion(questionId).isEmpty())

        // The same load path again — what the overflow action triggers.
        loadSample()

        val after = db.optionDao().getByQuestion(questionId)
        assertEquals(
            "the reload rewrites every one of the asset's ${expectedOptionIds.size} options " +
                "(${expectedOptionIds.sorted()})",
            expectedOptionIds,
            after.map { it.id }.toSet()
        )
        assertTrue(
            "the option's text label is restored",
            after.single { it.id == "b" }.searchText.contains("Square")
        )
        assertEquals(
            "the correct key is still the same option id",
            setOf("b"),
            db.correctAnswerDao().getCorrectIds(questionId).toSet()
        )
        assertEquals(
            "a reload updates in place, it does not duplicate the question",
            questionsBefore,
            db.questionDao().countForPaper(paperId)
        )
        assertEquals(
            "exactly one row still carries the id",
            1,
            db.questionDao().getByIds(listOf(questionId)).size
        )
    }

    private companion object {
        /** The bundled sample every "Load sample paper" action imports. */
        const val SAMPLE_ASSET = "src/main/assets/sample_paper.json"
    }
}
