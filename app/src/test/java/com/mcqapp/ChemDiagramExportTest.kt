package com.mcqapp

import com.mcqapp.data.export.ChemDiagramPreparation
import com.mcqapp.data.export.ChemDiagramRenderer
import com.mcqapp.data.export.DocxPaperWriter
import com.mcqapp.data.export.HtmlPaperWriter
import com.mcqapp.data.export.PaperStream
import com.mcqapp.data.export.ZipPaperWriter
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
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * The writers consume a prepared stream: a structure with no stored diagram
 * must reach DOCX, HTML and ZIP as a pictured structure, not as text handles
 * and not as a base64 payload printed into the document. Without a renderer the
 * same writers must behave exactly as before. (Anki needs Robolectric, so its
 * half lives in ChemDiagramAnkiExportTest.)
 */
class ChemDiagramExportTest {

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

    private val body = ContentElement.ChemElement(
        smiles = "c1ccccc1",
        formula = "C6H6",
        caption = "benzene"
    )

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
                            body,
                            ContentElement.TableElement(
                                listOf(
                                    listOf(
                                        "Name",
                                        chemCellHtml(
                                            ContentElement.ChemElement(
                                                smiles = "CCO",
                                                formula = "C2H5OH",
                                                caption = "ethanol"
                                            ),
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

    private val renderer = object : ChemDiagramRenderer {
        val calls = mutableListOf<String>()
        override suspend fun render(smiles: String, textAbove: String): String? {
            calls += smiles
            return png
        }
    }

    private fun prepared(): PaperStream = runBlocking {
        ChemDiagramPreparation.enrichPaper(PaperStream.of(paper), renderer)
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

    private fun html(stream: PaperStream): String = buildString {
        val sink = this
        runBlocking { HtmlPaperWriter.writeHtml(stream, sink) }
    }

    private fun docx(stream: PaperStream): ByteArray = exportBytes {
        DocxPaperWriter.writeDocx(stream, it)
    }

    private fun zip(stream: PaperStream): ByteArray = exportBytes {
        ZipPaperWriter.paperToZip(stream, it)
    }

    @Test
    fun htmlDrawsTheRenderedStructureInsteadOfHandlesOnly() {
        val page = html(prepared())
        assertTrue("the picture replaces the handles-only fallback", page.contains("<img src=\"$png\""))
        assertTrue("the readable handles still print", page.contains("benzene"))
    }

    @Test
    fun docxEmbedsTheRenderedStructureAsAPicture() {
        val files = entries(docx(prepared()))
        val xml = files.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue(xml.contains("benzene"))
        val media = files.filterKeys { it.startsWith("word/media/") }
        assertTrue("the structure must become a real media part", media.isNotEmpty())
        assertTrue("the embedded bytes are the rendered PNG",
            media.values.all { it.contentEquals(pngBytes) })
        assertFalse("no base64 payload leaks into the document XML",
            xml.contains(Base64.getEncoder().encodeToString(pngBytes)))
    }

    @Test
    fun zipStoresTheRenderedStructureAsAnImageFile() {
        val files = entries(zip(prepared()))
        val image = files.keys.single { it.startsWith("images/") }
        assertTrue(files.getValue(image).contentEquals(pngBytes))
        val json = files.getValue("paper.json").toString(Charsets.UTF_8)
        assertTrue("the JSON refers to the file, never to base64", json.contains(image))
        assertFalse("the rendered payload is not pasted into the JSON",
            json.contains(Base64.getEncoder().encodeToString(pngBytes)))
    }

    @Test
    fun withoutARendererEveryWriterKeepsTheHandlesFallback() {
        val raw = PaperStream.of(paper)

        val page = html(raw)
        assertTrue(page.contains("benzene"))
        assertFalse(page.contains("<img src=\"data:image/png"))

        val docxFiles = entries(docx(raw))
        assertTrue(docxFiles.keys.none { it.startsWith("word/media/") })
        assertTrue(docxFiles.getValue("word/document.xml").toString(Charsets.UTF_8).contains("benzene"))

        val zipFiles = entries(zip(raw))
        assertTrue(zipFiles.keys.none { it.startsWith("images/") })
        assertTrue(zipFiles.getValue("paper.json").toString(Charsets.UTF_8).contains("benzene"))
    }
}
