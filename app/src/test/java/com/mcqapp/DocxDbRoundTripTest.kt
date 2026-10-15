package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.export.DocxPaperWriter
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.data.export.PaperExporter
import com.mcqapp.data.docx.parseDocx
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.local.AppDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The full device loop against a real database: import the sample bank,
 * export it as Word, re-import the export. Every question — including the
 * id-less one whose options carry scalar pictures — must come back as a
 * duplicate skip, never as a new insert or an update. This is the
 * regression for the 0.0.20 report "export, re-import: 32 of 53 added as
 * new", whose last holdout was one question updating on every cycle
 * because the structured options shape moved an option's picture from the
 * scalar field into the element list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocxDbRoundTripTest {

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

    private fun sampleJson(): String =
        listOf("src/main/assets/sample_paper.json", "app/src/main/assets/sample_paper.json")
            .firstNotNullOfOrNull { File(it).takeIf { f -> f.isFile } }
            ?.readText()
            ?: error("sample_paper.json not found")

    @Test
    fun reimportingAnExportUpdatesNothing() = runBlocking {
        val first = Importer(db).import(LegacyParser.parse(sampleJson()))
        assertEquals(53, first.newQuestions)

        val exporter = PaperExporter(db)
        val docx = exporter.exportPaper("paper-demo", ExportFormat.DOCX)

        val parsed = parseDocx(docx.bytes)
        assertEquals(
            "payload must be accepted as fresh",
            emptyList<String>(),
            parsed.warnings.filter { it.contains("hidden question data") }
        )
        val second = Importer(db).import(LegacyParser.parse(parsed.json))
        assertEquals(
            "re-import must not add questions",
            0,
            second.newQuestions
        )
        assertEquals(
            "re-import must not update questions (the option-image round trip must be hash-stable)",
            0,
            second.updatedQuestions
        )
        assertEquals(53, second.duplicateQuestions)
    }
}
