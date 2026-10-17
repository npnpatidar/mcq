package com.mcqapp.data.export

import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.domain.ContentElement
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.OutputStream
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
 */
object ZipPaperWriter {

    private val json = Json { prettyPrint = true }

    /**
     * Writes the archive straight to [out]: the JSON is encoded once and each
     * image is piped in as it is reached, so the finished zip never exists as a
     * second full copy of the payload.
     */
    fun paperToZip(paper: PaperDto, out: OutputStream) {
        val files = LinkedHashMap<String, ByteArray>()
        val uriToPath = HashMap<String, String>()
        var counter = 0

        fun ref(src: String?): String? {
            if (src == null) return null
            val img = parseDataUri(src) ?: return src
            return uriToPath.getOrPut(src) {
                counter++
                val path = "images/img%03d.%s".format(counter, imageExtension(img.mimeType))
                files[path] = img.bytes
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

        fun remapQuestion(q: com.mcqapp.data.io.QuestionDto) = q.copy(
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
        val newPassages = paper.passages.map { p ->
            p.copy(image = ref(p.image), elements = remapElements(p.elements))
        }
        val newCategories = paper.categories.map { cat ->
            cat.copy(questions = cat.questions.map { q -> remapQuestion(q) })
        }
        val newPaper = paper.copy(
            categories = newCategories,
            questions = paper.questions.map { q -> remapQuestion(q) },
            // The paper's own copy of its passages needs the same treatment:
            // otherwise paper.json carries the original data URI here while the
            // top-level passages list points at the file.
            passages = newPassages
        )
        val jsonText = json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = listOf(newPaper), passages = newPassages)
        )

        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("paper.json"))
            zip.write(jsonText.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((path, bytes) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** The same archive as bytes, for tests and small callers. */
    fun paperToZipBytes(paper: PaperDto): ByteArray =
        ByteArrayOutputStream().also { paperToZip(paper, it) }.toByteArray()
}
