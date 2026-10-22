package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.data.export.PaperExporter
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.local.CategoryEntity
import com.mcqapp.data.local.PaperEntity
import com.mcqapp.data.repository.McqRepository
import com.mcqapp.domain.Question
import com.mcqapp.domain.QuestionOption
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scope-aware export (E2 of `category_card_counts.md` §14): the card's
 * ticked branches travel through `Exporter.categoryStream`'s union walk, so
 * an export of two branches holds exactly their questions — and an empty
 * selection is an error the ViewModel's dialog can show.
 *
 * **CI-only: opens a real SQLite database; this host is aarch64.** It still
 * has to compile here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaperExporterSelectionTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: McqRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = McqRepository(db, context)
        // The DataStore is process-wide under Robolectric; leave it as found.
        repository.resetSchedulerConfig()

        db.paperDao().insertIgnore(PaperEntity(id = "p1", title = "Selection"))
        category("c1", "First", 0)
        category("c2", "Second", 1)
        category("c3", "Third", 2)
        question("q1", "c1")
        question("q2", "c2")
        // The unticked branch: its question must never ride along.
        question("q3", "c3")
    }

    @After
    fun tearDown() = runBlocking {
        repository.resetSchedulerConfig()
        db.close()
    }

    @Test
    fun theSelectionExportsExactlyItsBranches() = runBlocking {
        val sink = ByteArrayOutputStream()
        val file = PaperExporter(db).exportSelection(
            paperId = "p1",
            rootCategoryIds = listOf("c1", "c2"),
            format = ExportFormat.JSON_INLINE,
            sink = sink
        )

        assertEquals(ExportFormat.JSON_INLINE.mimeType, file.mimeType)
        assertEquals("Selection.json", file.fileName)

        // Read the envelope back: paper -> categories -> questions.
        val json = JSONObject(sink.toString("UTF-8"))
        val paper = json.getJSONArray("papers").getJSONObject(0)
        val categories = paper.getJSONArray("categories")
        assertEquals("exactly the two selected branches", 2, categories.length())

        val titles = (0 until categories.length())
            .map { categories.getJSONObject(it).getString("title") }
        assertEquals(listOf("First", "Second"), titles)

        val questionIds = (0 until categories.length()).flatMap { index ->
            val rows = categories.getJSONObject(index).getJSONArray("questions")
            (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
        }
        assertEquals("exactly one question per selected branch", listOf("q1", "q2"), questionIds)
    }

    @Test
    fun anEmptySelectionIsAnError() = runBlocking {
        // E2's guard: ids always come from the live tree, so an empty pick (or
        // one that resolves to no category) is the only way to reach it — and
        // it must be a message, not a silently empty document.
        val thrown = try {
            PaperExporter(db).exportSelection(
                paperId = "p1",
                rootCategoryIds = emptyList(),
                format = ExportFormat.JSON_INLINE,
                sink = ByteArrayOutputStream()
            )
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertEquals(
            "The selected categories have no questions to export.",
            thrown?.message
        )
    }

    @Test
    fun aSelectionResolvingToNoCategoryIsAlsoAnError() = runBlocking {
        // A stale id (deleted category, old saved selection) expands to no
        // category at all: the same guard, not an empty document.
        val thrown = try {
            PaperExporter(db).exportSelection(
                paperId = "p1",
                rootCategoryIds = listOf("ghost"),
                format = ExportFormat.JSON_INLINE,
                sink = ByteArrayOutputStream()
            )
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertEquals(
            "The selected categories have no questions to export.",
            thrown?.message
        )
    }

    private suspend fun category(
        id: String,
        title: String,
        sortOrder: Int,
        parentId: String? = null
    ) = db.categoryDao().upsert(
        CategoryEntity(
            id = id,
            paperId = "p1",
            title = title,
            parentId = parentId,
            sortOrder = sortOrder
        )
    )

    private suspend fun question(id: String, categoryId: String) =
        repository.saveQuestion(
            Question(
                id = id,
                categoryId = categoryId,
                text = "Q $id",
                options = listOf(
                    QuestionOption("$id-a", "A"),
                    QuestionOption("$id-b", "B")
                ),
                correctOptionIds = setOf("$id-a")
            )
        )
}
