package com.mcqapp.data.export

import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import com.mcqapp.domain.ContentElement
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Renders a paper as a .zip containing `paper.json` plus an `images/` folder.
 * Embedded (data-URI) images become files that the JSON refers to by relative
 * path; remote URLs are left untouched.
 *
 * Images are collected wherever they sit: the legacy per-row fields and the
 * image blocks inside a stem, an option, an explanation or a passage. Walking
 * only the legacy fields left every block-editor image inline — the JSON grew
 * by the whole bank's base64 and the folder stayed empty.
 *
 * Both the JSON and the folder are written while the paper streams past: the
 * JSON is encoded question by question straight into its entry, and each
 * image is decoded once and spooled to a temp file ([MediaSpool]). Only the
 * images one paper references are held (deduplicated by src — the set the
 * folder ends up with), never the paper's question graph, and the spool itself
 * holds one image at a time rather than the paper's worth of pictures.
 */
object ZipPaperWriter {

    suspend fun paperToZip(stream: PaperStream, out: OutputStream) {
        val spool = MediaSpool()
        val uriToPath = HashMap<String, String>()
        var counter = 0

        fun ref(src: String?): String? {
            if (src == null) return null
            val img = parseDataUri(src) ?: return src
            return uriToPath.getOrPut(src) {
                counter++
                val path = "images/img%03d.%s".format(counter, imageExtension(img.mimeType))
                spool.add(path, img.bytes)
                path
            }
        }

        /** The image blocks of a content list, repointed at their files. */
        fun remapElements(elements: List<ContentElement>): List<ContentElement> =
            elements.map { element ->
                if (element is ContentElement.ImageElement) {
                    ContentElement.ImageElement(ref(element.src) ?: element.src)
                } else {
                    element
                }
            }

        fun remapQuestion(q: QuestionDto) = q.copy(
            image = ref(q.image),
            explanationImage = ref(q.explanationImage),
            elements = remapElements(q.elements),
            explanationElements = remapElements(q.explanationElements),
            options = q.options.map { o ->
                o.copy(image = ref(o.image), elements = remapElements(o.elements))
            }
        )

        // Passages travel too; their images get the same relative-path
        // treatment so the json alone never carries raw data URIs.
        fun remapPassage(p: PassageDto) = p.copy(
            image = ref(p.image),
            elements = remapElements(p.elements)
        )

        try {
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("paper.json"))
                val jsonWriter = OutputStreamWriter(zip, Charsets.UTF_8)
                PaperJsonWriter.writePaperJson(
                    jsonWriter,
                    stream,
                    question = ::remapQuestion,
                    passage = ::remapPassage
                )
                // Flush, never close: closing would take the zip (and the caller's
                // stream) with it.
                jsonWriter.flush()
                zip.closeEntry()
                // After the JSON: the passing walk named every picture it met.
                for (path in spool.names) {
                    zip.putNextEntry(ZipEntry(path))
                    spool.copyTo(path, zip)
                    zip.closeEntry()
                }
            }
        } finally {
            spool.close()
        }
    }
}
