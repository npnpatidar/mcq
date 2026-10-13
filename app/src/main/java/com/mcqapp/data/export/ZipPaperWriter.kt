package com.mcqapp.data.export

import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Renders a paper as a .zip containing `paper.json` plus an `images/` folder.
 * Embedded (data-URI) images become files that the JSON refers to by relative
 * path; remote URLs are left untouched.
 */
object ZipPaperWriter {

    private val json = Json { prettyPrint = true }

    fun paperToZipBytes(paper: PaperDto): ByteArray {
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

        fun remapQuestion(q: com.mcqapp.data.io.QuestionDto) = q.copy(
            image = ref(q.image),
            explanationImage = ref(q.explanationImage),
            options = q.options.map { o -> o.copy(image = ref(o.image)) }
        )
        // Passages travel too; their images get the same relative-path
        // treatment so the json alone never carries raw data URIs.
        val newPassages = paper.passages.map { p -> p.copy(image = ref(p.image)) }
        val newCategories = paper.categories.map { cat ->
            cat.copy(questions = cat.questions.map { q -> remapQuestion(q) })
        }
        val newPaper = paper.copy(
            categories = newCategories,
            questions = paper.questions.map { q -> remapQuestion(q) }
        )
        val jsonText = json.encodeToString(
            McqFileDto.serializer(),
            McqFileDto(version = 1, papers = listOf(newPaper), passages = newPassages)
        )

        val out = ByteArrayOutputStream()
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
        return out.toByteArray()
    }
}
