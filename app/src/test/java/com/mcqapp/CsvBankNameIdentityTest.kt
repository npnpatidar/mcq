package com.mcqapp

import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.Importer
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.ui.importscreen.ImportViewModel
import com.mcqapp.ui.importscreen.buildImportFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A flat CSV/TSV bank has no title of its own, so the import preview names the
 * new paper after the picked file.
 *
 * INCIDENT: the picker's display name was dropped on the way to
 * `loadCsvTsv`, so every CSV became "Imported Questions". Importing a second
 * bank then matched that title and merged it into the first paper — two
 * unrelated banks, one paper, with the second bank's ids remapped to avoid a
 * collision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestMcqApplication::class)
class CsvBankNameIdentityTest {

    private lateinit var app: McqApplication
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext() as McqApplication
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun awaitIdle(condition: () -> Boolean): Boolean {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /** Drives the real picker path: bytes plus the file's display name. */
    private fun loadNamed(fileName: String, csv: String): ImportViewModel {
        val viewModel = ImportViewModel(app)
        viewModel.loadJsonBytes(csv.toByteArray(Charsets.UTF_8), fileName)
        assertTrue(
            "the CSV preview must load",
            awaitIdle {
                !viewModel.state.value.loading && viewModel.state.value.questions.isNotEmpty()
            }
        )
        return viewModel
    }

    @Test
    fun twoDifferentlyNamedCsvFilesCreateTwoPapers() = runBlocking {
        val first = loadNamed(
            "Algebra.csv",
            "question,option1,option2,answer\nWhat is 2+2?,3,4,b\n"
        )
        assertEquals(
            "the picked file's name must become the preview paper title",
            "Algebra",
            first.state.value.paperTitle
        )
        Importer(db).import(buildImportFile(first.state.value, first.state.value.originalFile))

        // The CSV parser mints its paper id from the current millisecond; make
        // sure the two banks cannot accidentally share it and be matched by id
        // before the title path under test is even reached.
        Thread.sleep(5)
        val second = loadNamed(
            "Geography.csv",
            "question,option1,option2,answer\nCapital of India?,Mumbai,New Delhi,b\n"
        )
        assertEquals("Geography", second.state.value.paperTitle)
        Importer(db).import(buildImportFile(second.state.value, second.state.value.originalFile))

        val papers = db.paperDao().getAll()
        assertEquals(
            "two differently named banks must not merge: ${papers.map { it.title }}",
            2,
            papers.size
        )
        assertEquals(listOf("Algebra", "Geography"), papers.map { it.title }.sorted())
        assertNotEquals(papers[0].id, papers[1].id)
    }
}
