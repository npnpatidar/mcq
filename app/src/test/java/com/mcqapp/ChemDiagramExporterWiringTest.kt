package com.mcqapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mcqapp.data.export.ChemDiagramRenderer
import com.mcqapp.data.export.ChemDiagramRendererProvider
import com.mcqapp.data.export.ExportFormat
import com.mcqapp.data.export.PaperExporter
import com.mcqapp.data.local.AppDatabase
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.OptionDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * The real exporter must consume the preparation pass, not just the helper.
 * A fake renderer is installed through the process-wide provider, `PaperExporter`
 * writes DOCX and HTML, and both must carry the drawn structure. Removing the
 * prepass call from `PaperExporter.render` makes both assertions fail.
 *
 * Robolectric only for the in-memory database the exporter's constructor wants;
 * DOCX/HTML never query it. CI is authoritative for this class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChemDiagramExporterWiringTest {

    private lateinit var db: AppDatabase

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
                            ContentElement.TextElement("Identify:"),
                            ContentElement.ChemElement(
                                smiles = "c1ccccc1",
                                formula = "C6H6",
                                caption = "benzene"
                            )
                        ),
                        options = listOf(OptionDto(id = "a", text = "Benzene"))
                    )
                )
            )
        )
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        // The provider is process-wide: never leak a fake into another test.
        ChemDiagramRendererProvider.install(null)
        db.close()
    }

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
    fun docxAndHtmlDrawTheStructureThroughTheInstalledProvider() = runBlocking {
        ChemDiagramRendererProvider.install(object : ChemDiagramRenderer {
            override suspend fun render(smiles: String, textAbove: String): String = png
        })
        val exporter = PaperExporter(db)

        val html = ByteArrayOutputStream()
        exporter.exportDto(paper, "Chem", ExportFormat.HTML, html)
        assertTrue(
            "HTML through the real exporter must draw the structure: ${html.toString("UTF-8")}",
            html.toString("UTF-8").contains("<img src=\"$png\"")
        )

        val docx = ByteArrayOutputStream()
        exporter.exportDto(paper, "Chem", ExportFormat.DOCX, docx)
        val files = entries(docx.toByteArray())
        val media = files.filterKeys { it.startsWith("word/media/") }
        assertTrue("DOCX through the real exporter must embed the structure", media.isNotEmpty())
        assertTrue(media.values.all { it.contentEquals(pngBytes) })

        // The same exporter with no provider keeps today's handles-only output.
        ChemDiagramRendererProvider.install(null)
        val raw = ByteArrayOutputStream()
        exporter.exportDto(paper, "Chem", ExportFormat.HTML, raw)
        assertFalse("without a renderer HTML stays on the handles path",
            raw.toString("UTF-8").contains("<img src=\"data:image/png"))
    }
}
