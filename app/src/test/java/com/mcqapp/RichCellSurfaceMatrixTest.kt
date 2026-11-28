package com.mcqapp

import androidx.room.Room
import com.mcqapp.data.backup.BackupRestorer
import com.mcqapp.data.backup.BackupSource
import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.export.PaperJsonWriter
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.io.CardContent
import com.mcqapp.data.io.Exporter
import com.mcqapp.data.io.Importer
import com.mcqapp.data.io.LegacyParser
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.domain.ContentElement
import com.mcqapp.domain.searchableText
import com.mcqapp.util.CELL_CHEM_CLASS
import com.mcqapp.util.CELL_IMAGE_ALT
import com.mcqapp.util.CellSegment
import com.mcqapp.util.cellSegments
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The Room-backed half of the rich-cell surface matrix: backup/restore, JSON
 * export/import and the bundled sample. The shared fixture is one row carrying
 * prose, an inline `<math>` formula, a canonical portable `<img>` and a
 * `mcq-chem-cell` structure, in the stem, an option and the explanation
 * ([RichCellFixtures]).
 *
 * Both data paths are the production ones, not the in-memory seams:
 *  - backup writes with [McqBackupFormat.writeTo], [Exporter.exportAllTo] and
 *    [BackupSource.emitCards]/[BackupSource.emitReviews], exactly as
 *    `SettingsViewModel.backupAll` does, and restores with
 *    [BackupRestorer.restoreStreaming], exactly as `SettingsViewModel.restoreFrom`
 *    does. `McqBackupFormat.write`, `BackupSource.collect` and
 *    `BackupRestorer.restore` have no production caller.
 *  - JSON goes through [PaperJsonWriter] and [LegacyParser] into the production
 *    [Importer].
 *
 * Robolectric because all three open a real Room database. That is CI-only on
 * this ARM64 host; the render-path and TalkBack cases are pure JVM and live in
 * [RichCellSpeechAndPathTest], which `:app:testPureJvm` runs here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RichCellSurfaceMatrixTest {

    private val databases = mutableListOf<AppDatabase>()

    private fun newDatabase(): AppDatabase {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { databases += it }
    }

    @After
    fun closeDatabases() {
        databases.forEach { it.close() }
    }

    // ---- 1. backup -> restore (SettingsViewModel's chain) -----------------

    @Test
    fun theRichCellsSurviveTheStreamingBackupAndRestoreUnrewritten() = runBlocking {
        val source = newDatabase()
        Importer(source).import(RichCellFixtures.file())
        val before = RichCellFixtures.questionOf(
            Exporter(source).exportAllDto(withScheduling = false)
        )

        // SettingsViewModel.backupAll, step for step: the same manifest counts,
        // the streamed content export and the streamed study rows.
        val paperRows = source.paperDao().getAll()
        val manifest = McqBackupFormat.Manifest(
            dbVersion = source.openHelper.readableDatabase.version,
            appVersion = "rich-cell-test",
            exportedAt = 0,
            papers = paperRows.size,
            questions = source.questionDao().countQuestions(),
            attempts = source.attemptDao().countAll(),
            reviews = source.reviewLogDao().countAll()
        )
        val archive = ByteArrayOutputStream()
        McqBackupFormat.writeTo(
            output = archive,
            manifest = manifest,
            content = { writer ->
                Exporter(source).exportAllTo(writer, withScheduling = false)
            },
            study = { writer ->
                val backupSource = BackupSource(source)
                McqBackupFormat.writeStudyRows(
                    w = writer,
                    cardState = { emit -> backupSource.emitCards(emit) },
                    reviewLog = { emit -> backupSource.emitReviews(emit) },
                    paperCreated = paperRows.map {
                        McqBackupFormat.PaperCreated(it.id, it.createdAt, it.sortOrder)
                    },
                    inProgressTest = null,
                    panels = backupSource.panels(),
                    blueprints = backupSource.backupBlueprints(),
                    gradingVersions = backupSource.gradingVersions(),
                    studySessions = backupSource.studySessions()
                )
            },
            settings = McqBackupFormat.SettingsFile()
        )

        // SettingsViewModel.restoreFrom: the zip is decoded as it is read.
        val target = newDatabase()
        val report = BackupRestorer(target)
            .restoreStreaming(ByteArrayInputStream(archive.toByteArray()))
        assertTrue("the restore must count the question: $report", report.contains("1 questions"))
        val after = RichCellFixtures.questionOf(
            Exporter(target).exportAllDto(withScheduling = false)
        )

        assertEquals("stem, option and explanation blocks", before.elements, after.elements)
        assertEquals(before.explanationElements, after.explanationElements)
        assertEquals(before.options.map { it.elements }, after.options.map { it.elements })
        assertEquals("the schedule identity must not move", CardContent.of(before), CardContent.of(after))
        assertEquals("no cell string may be rewritten", RichCellFixtures.cells(before), RichCellFixtures.cells(after))
        assertEquals(
            "the restored cells are the authored cells",
            RichCellFixtures.cells(RichCellFixtures.question()),
            RichCellFixtures.cells(after)
        )
        assertEquals(before.id, after.id)
        assertEquals(before.options.map { it.id }, after.options.map { it.id })
        assertEquals(before.correctOptionIds, after.correctOptionIds)
    }

    // ---- 2. JSON export -> parse -> import --------------------------------

    @Test
    fun jsonExportParsesBackWithStableIdsAndNoWarnings() = runBlocking {
        val original = RichCellFixtures.question()
        val paper = RichCellFixtures.file().papers.single()
        val out = StringBuilder()
        PaperJsonWriter.writePaperJson(out, PaperStream.of(paper))

        val parsedFile = LegacyParser.parse(out.toString())
        assertTrue("the app's own export must not warn: ${parsedFile.warnings}", parsedFile.warnings.isEmpty())
        val parsed = RichCellFixtures.questionOf(parsedFile)

        // The whole DTO, not just the elements: a dropped tags/difficulty/marks/
        // text/explanation field must fail here, not hide behind the fixpoint.
        assertEquals(original, parsed)
        assertEquals(original.id, parsed.id)
        assertEquals(original.options.map { it.id }, parsed.options.map { it.id })
        assertEquals(original.correctOptionIds, parsed.correctOptionIds)

        // A second pass over the parsed bank changes nothing — not one element
        // and not one envelope field.
        val secondOut = StringBuilder()
        PaperJsonWriter.writePaperJson(secondOut, PaperStream.of(parsedFile.papers.single()))
        val secondFile = LegacyParser.parse(secondOut.toString())
        assertTrue(secondFile.warnings.isEmpty())
        assertEquals(parsedFile, secondFile)
    }

    @Test
    fun jsonExportImportsBackWithoutRewritingTheCells() = runBlocking {
        val source = newDatabase()
        Importer(source).import(RichCellFixtures.file())
        val storedFile = Exporter(source).exportAllDto(withScheduling = false)
        val stored = RichCellFixtures.questionOf(storedFile)

        val out = StringBuilder()
        PaperJsonWriter.writePaperJson(out, PaperStream.of(storedFile.papers.single()))
        val parsedFile = LegacyParser.parse(out.toString())
        assertTrue(parsedFile.warnings.isEmpty())

        val target = newDatabase()
        Importer(target).import(parsedFile)
        val reimported = RichCellFixtures.questionOf(
            Exporter(target).exportAllDto(withScheduling = false)
        )

        assertEquals(stored.id, reimported.id)
        assertEquals(stored.options.map { it.id }, reimported.options.map { it.id })
        assertEquals(stored.elements, reimported.elements)
        assertEquals(stored.explanationElements, reimported.explanationElements)
        assertEquals(stored.options.map { it.elements }, reimported.options.map { it.elements })
        assertEquals(RichCellFixtures.cells(stored), RichCellFixtures.cells(reimported))
        assertEquals(
            "the import must not rewrite the authored cells",
            RichCellFixtures.cells(RichCellFixtures.question()),
            RichCellFixtures.cells(reimported)
        )
        assertEquals(CardContent.of(stored), CardContent.of(reimported))
        assertEquals(
            "search must see the same flattened text after import",
            stored.elements.searchableText,
            reimported.elements.searchableText
        )
    }

    // ---- 3. the bundled sample through the real importer ------------------

    /**
     * The bundled bank's rich-table demo, imported the way the app loads it.
     *
     * Not a pre-branch guard: `q-chem-6` and its canonical
     * `alt="Image in table"` are introduced by this branch. The test pins that
     * the new content survives the importer and that search keeps the readable
     * handles while dropping the payload and the marker markup.
     * [SampleChemistryTest.theRichTableCellQuestionKeepsTextFormulaImageAndStructure]
     * already pins the four payloads and the positive search hits at parse
     * time; this adds the importer/storage level and the negative search
     * assertion.
     */
    @Test
    fun theSampleRichTableKeepsItsPayloadsAndStaysOutOfTheSearchPayload() = runBlocking {
        val db = newDatabase()
        val file = LegacyParser.parse(RichCellFixtures.sampleJson())
        assertTrue("the bundled sample must parse cleanly: ${file.warnings}", file.warnings.isEmpty())
        Importer(db).import(file)

        val question = RichCellFixtures.questionOf(
            Exporter(db).exportAllDto(withScheduling = false),
            "q-chem-6"
        )
        val table = question.elements.filterIsInstance<ContentElement.TableElement>().single()

        assertEquals(listOf("Name", "Formula", "Structure", "Picture"), table.rows[0])
        val row = table.rows[1]
        assertEquals("four payloads, in their columns", 4, row.size)
        assertEquals("the prose payload", "Water", row[0])
        assertEquals("the formula payload", RichCellFixtures.formulaCell, row[1])

        assertTrue(
            "the structure payload: ${row[2]}",
            row[2].startsWith("<span class=\"$CELL_CHEM_CLASS\"")
        )
        val storedStructure = cellSegments(row[2], withPng = false)
            .filterIsInstance<CellSegment.Chem>().single().element
        assertEquals("O", storedStructure.smiles)
        assertEquals("H₂O", storedStructure.formula)
        assertEquals("water", storedStructure.caption)

        assertTrue(
            "the picture payload: ${row[3]}",
            row[3].startsWith("<img src=\"data:image/png;base64,")
        )
        assertTrue(row[3].contains("alt=\"$CELL_IMAGE_ALT\""))

        val stored = db.questionDao().getById("q-chem-6")!!.searchText
        assertEquals(
            "the stored search column and the elements must flatten identically",
            question.elements.searchableText,
            stored
        )
        assertTrue("the handles must be searchable: $stored", stored.contains("water H₂O O"))
        assertTrue("the formula cell must be searchable: $stored", stored.contains("H 2 O"))
        assertFalse("no base64 payload may reach search: $stored", stored.contains("base64"))
        assertFalse("no data URI may reach search: $stored", stored.contains("data:image"))
        assertFalse("no marker class may reach search: $stored", stored.contains(CELL_CHEM_CLASS))
        assertFalse("no marker markup may reach search: $stored", stored.contains("<span"))
        assertFalse("no live image tag may reach search: $stored", stored.contains("<img"))
    }
}
