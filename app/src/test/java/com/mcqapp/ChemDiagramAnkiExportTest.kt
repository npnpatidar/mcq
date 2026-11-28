package com.mcqapp

import android.database.sqlite.SQLiteDatabase
import com.mcqapp.data.anki.AnkiPackageWriter
import com.mcqapp.data.export.ChemDiagramPreparation
import com.mcqapp.data.export.ChemDiagramRenderer
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import com.mcqapp.util.chemCellHtml
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Anki's writer opens a real SQLite database, so it needs Robolectric (CI is
 * authoritative on x86_64). A prepared structure must spool as `mcqapp-N.png`;
 * without a renderer the same export must write no media, exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChemDiagramAnkiExportTest {

    private val pngBytes = ByteArray(24).also { b ->
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        sig.copyInto(b, 0)
        'I'.code.toByte().let { b[8] = it }
        'H'.code.toByte().let { b[9] = it }
        'D'.code.toByte().let { b[10] = it }
        'R'.code.toByte().let { b[11] = it }
        b[16] = 0; b[17] = 0; b[18] = 0; b[19] = 2
        b[20] = 0; b[21] = 0; b[22] = 0; b[23] = 2
    }
    private val png = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)

    private val paper = PaperDto(
        id = "p1",
        title = "Chemistry",
        categories = listOf(
            CategoryDto(
                id = "c1",
                title = "Structures",
                questions = listOf(
                    QuestionDto(
                        id = "q1",
                        elements = listOf(
                            ContentElement.ChemElement(
                                smiles = "c1ccccc1",
                                formula = "C6H6",
                                caption = "benzene"
                            ),
                            ContentElement.TableElement(
                                listOf(
                                    listOf(
                                        chemCellHtml(
                                            ContentElement.ChemElement(smiles = "CCO"),
                                            includePng = false
                                        )
                                    )
                                )
                            )
                        ),
                        options = listOf(OptionDto(id = "a", text = "Benzene"))
                    )
                )
            )
        )
    )

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun apkg(stream: PaperStream): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { AnkiPackageWriter.writeTo(stream, out) }
        return out.toByteArray()
    }

    @Test
    fun aPreparedStructureIsSpooledAsMedia() {
        val renderer = object : ChemDiagramRenderer {
            override suspend fun render(smiles: String, textAbove: String): String = png
        }
        val stream = runBlocking {
            ChemDiagramPreparation.enrichPaper(PaperStream.of(paper), renderer)
        }
        val files = entries(apkg(stream))
        val manifest = files.getValue("media").toString(Charsets.UTF_8)
        assertTrue("the media entry names an image file: $manifest",
            manifest.contains("mcqapp-0.png"))
        assertTrue(files.getValue("0").contentEquals(pngBytes))

        // The note field Anki renders must reference the spooled filename and
        // must never print the raster as base64. The third field is the hidden
        // structured payload the app reads back, so it is not part of this
        // visible-field assertion.
        val dbFile = File.createTempFile("chem-diagram-anki", ".anki2")
        try {
            dbFile.writeBytes(files.getValue("collection.anki2"))
            val db = SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READONLY
            )
            try {
                db.rawQuery("select flds from notes", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    val fields = rows.getString(0).split(AnkiPackageWriter.FIELD_SEPARATOR)
                    val visible = fields.take(2).joinToString(" ")
                    assertTrue(
                        "the visible note must reference the spooled media: $visible",
                        visible.contains("<img src=\"mcqapp-0.png\" alt=\"benzene C6H6\">")
                    )
                    assertFalse(
                        "the handles are not printed beside the picture: $visible",
                        visible.contains("c1ccccc1")
                    )
                    assertFalse(
                        "the visible note must not print a base64 payload: $visible",
                        visible.contains("data:image/png") ||
                            visible.contains(Base64.getEncoder().encodeToString(pngBytes))
                    )
                }
            } finally {
                db.close()
            }
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun withoutARendererTheAnkiExportWritesNoMedia() {
        val files = entries(apkg(PaperStream.of(paper)))
        assertFalse("an Anki export without drawings writes no media entry", "media" in files)
    }
}
