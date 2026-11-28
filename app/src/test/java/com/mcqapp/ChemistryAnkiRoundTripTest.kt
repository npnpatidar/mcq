package com.mcqapp

import android.database.sqlite.SQLiteDatabase
import com.mcqapp.data.anki.AnkiPackageReader
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The whole Anki path for a chemical structure: export a note, read the deck
 * back with the app's own importer, and check both that the ChemElement comes
 * through the third-field payload unchanged *and* that its readable front
 * references the spooled `mcqapp-0.png` media by filename (the only reference
 * Anki can resolve). SQLite needs Robolectric; CI is authoritative for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChemistryAnkiRoundTripTest {

    private val png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="
    private val chem = ContentElement.ChemElement(
        smiles = "c1ccccc1",
        formula = "C6H6",
        caption = "Benzene ring",
        spoken = "aromatic ring",
        diagram = png
    )

    private fun paper() = PaperDto(
        id = "p1",
        title = "Chemistry",
        categories = listOf(
            CategoryDto(
                id = "c1",
                title = "Structures",
                questions = listOf(
                    QuestionDto(
                        id = "q-chem",
                        elements = listOf(ContentElement.TextElement("Identify:"), chem),
                        options = listOf(OptionDto("a", "Benzene")),
                        correctOptionIds = listOf("a")
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

    @Test
    fun aFullAnkiRoundTripKeepsTheChemElementAndResolvesItsPngMedia() {
        val apkg = apkgOf(paper())
        val files = entries(apkg)

        val manifest = Json.parseToJsonElement(String(files.getValue("media"), Charsets.UTF_8))
            .jsonObject
        assertEquals("mcqapp-0.png", manifest.getValue("0").jsonPrimitive.content)

        val read = AnkiPackageReader.read(apkg)
        assertEquals("every referenced picture must resolve", 0, read.missingMedia)
        val restored = read.file.papers.single().categories.single().questions.single()
        assertEquals(
            "the mcqapp payload carries the whole ChemElement, diagram included",
            listOf(chem),
            restored.elements.filterIsInstance<ContentElement.ChemElement>()
        )

        val dbFile = File.createTempFile("chem-anki-roundtrip", ".anki2")
        try {
            dbFile.writeBytes(files.getValue("collection.anki2"))
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("select flds from notes", null).use { rows ->
                    assertTrue(rows.moveToFirst())
                    val frontAndPayload = rows.getString(0)
                    assertTrue(
                        "the readable field must reference the media by filename: $frontAndPayload",
                        "<img src=\"mcqapp-0.png\" alt=\"aromatic ring C6H6\">" in frontAndPayload
                    )
                    assertFalse(
                        "the handles are not printed beside the picture: $frontAndPayload",
                        "Benzene ring C6H6 c1ccccc1" in frontAndPayload
                    )
                }
            } finally {
                db.close()
            }
        } finally {
            dbFile.delete()
        }
    }
}
