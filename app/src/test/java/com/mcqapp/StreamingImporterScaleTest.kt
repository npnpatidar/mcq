package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.io.StreamingImporter
import com.mcqapp.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * The stated shape the importer must survive: one paper with a thousand
 * categories, and a single category with more questions than one batch. The
 * per-batch design is what makes both flat in memory; these assert it also
 * finishes and keeps every row. Robolectric: runs in CI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingImporterScaleTest {

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

    private fun count(sql: String): Int {
        db.openHelper.writableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun bankWithCategories(categories: Int, perCategory: Int): String = buildString {
        append("""{"papers":[{"id":"p","title":"Thousand","categories":[""")
        for (c in 0 until categories) {
            if (c > 0) append(",")
            append("""{"id":"c$c","title":"Cat $c","questions":[""")
            for (q in 0 until perCategory) {
                if (q > 0) append(",")
                append(
                    """{"id":"c${c}q$q","text":"Q $c-$q","options":["a","b"],""" +
                        """"correctOptionIds":["a"]}"""
                )
            }
            append("]}")
        }
        append("]}]}")
    }

    @Test
    fun aThousandCategoriesImportCompletely() = runBlocking {
        val categories = 1000
        val perCategory = 4
        val json = bankWithCategories(categories, perCategory)

        val report = StreamingImporter(db, batchSize = 1000)
            .import(source = { ByteArrayInputStream(json.toByteArray()) })

        assertEquals(1, report.newPapers)
        assertEquals(categories * perCategory, report.newQuestions)
        assertEquals(categories, db.categoryDao().getByPaper("p").size)
        assertEquals(categories * perCategory, count("SELECT COUNT(*) FROM questions"))
    }

    @Test
    fun aPaperLargerThanOneBatchImportsAndReimportsAsDuplicates() = runBlocking {
        val total = 5000
        val json = bankWithCategories(categories = 1, perCategory = total)
        val progress = mutableListOf<Int>()

        val report = StreamingImporter(db, batchSize = 2000)
            .import({ ByteArrayInputStream(json.toByteArray()) }) { progress += it }

        assertEquals(total, report.newQuestions)
        assertEquals(listOf(2000, 4000, 5000), progress)
        assertEquals(total, count("SELECT COUNT(*) FROM questions"))

        // Idempotent: the same bytes again add nothing.
        val again = StreamingImporter(db, batchSize = 2000)
            .import(source = { ByteArrayInputStream(json.toByteArray()) })
        assertEquals(0, again.newQuestions)
        assertEquals(total, again.duplicateQuestions)
        assertEquals(total, count("SELECT COUNT(*) FROM questions"))
    }
}
