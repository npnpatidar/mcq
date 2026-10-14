package com.mcqapp

import com.mcqapp.data.backup.McqBackupFormat
import com.mcqapp.data.io.CategoryDto
import com.mcqapp.data.io.McqFileDto
import com.mcqapp.data.io.PaperDto
import com.mcqapp.data.io.PassageDto
import com.mcqapp.data.io.QuestionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zip layout is a contract with future versions of this app: the parts
 * are named, pretty-printed JSON, and a reader from any later version must
 * still refuse a file it cannot honestly restore.
 */
class McqBackupFormatTest {

    private fun content() = McqFileDto(
        version = 1,
        papers = listOf(
            PaperDto(
                id = "p1",
                title = "Paper",
                categories = listOf(
                    CategoryDto(
                        id = "c1",
                        title = "Cat",
                        questions = listOf(
                            QuestionDto(
                                id = "q1",
                                text = "Stem",
                                // Options travel as elements; the flat text
                                // is derived from them on the way back.
                                options = listOf(
                                    com.mcqapp.data.io.OptionDto(
                                        id = "a",
                                        text = "x",
                                        elements = listOf(com.mcqapp.domain.ContentElement.TextElement("x"))
                                    ),
                                    com.mcqapp.data.io.OptionDto(
                                        id = "b",
                                        text = "y",
                                        elements = listOf(com.mcqapp.domain.ContentElement.TextElement("y"))
                                    )
                                ),
                                correctOptionIds = listOf("a")
                            )
                        )
                    )
                )
            )
        ),
        passages = listOf(PassageDto(id = "ps1", title = "Passage"))
    )

    private fun write(
        manifest: McqBackupFormat.Manifest = McqBackupFormat.Manifest(
            format = McqBackupFormat.FORMAT,
            version = McqBackupFormat.VERSION,
            dbVersion = 10,
            exportedAt = 123L,
            papers = 1,
            questions = 1
        ),
        content: McqFileDto = content(),
        study: McqBackupFormat.StudyFile = McqBackupFormat.StudyFile(),
        settings: McqBackupFormat.SettingsFile = McqBackupFormat.SettingsFile()
    ): ByteArray = McqBackupFormat.write(manifest, content, study, settings)

    @Test
    fun roundTripKeepsEveryPart() {
        val study = McqBackupFormat.StudyFile(
            cardState = listOf(
                McqBackupFormat.CardStateRow(
                    paperId = "p1", questionId = "q1", ease = 2.5, intervalDays = 3,
                    dueAt = 99L, reps = 2, lapses = 0, leech = false,
                    lastReviewedAt = 50L, contentHash = "h", cardState = "REVIEW"
                )
            ),
            reviewLog = listOf(
                McqBackupFormat.ReviewLogRow(
                    id = 1, paperId = "p1", questionId = "q1", rating = 3, reviewedAt = 50L
                )
            ),
            inProgressTest = "{\"paperId\":\"p1\"}"
        )
        val settings = McqBackupFormat.SettingsFile(
            preferences = mapOf(
                "theme_mode" to McqBackupFormat.PreferenceEntry("string", kotlinx.serialization.json.JsonPrimitive("dark")),
                "font_scale" to McqBackupFormat.PreferenceEntry("float", kotlinx.serialization.json.JsonPrimitive(1.2f))
            )
        )
        val backup = McqBackupFormat.read(write(study = study, settings = settings))
        assertEquals(content(), backup.content)
        assertEquals(study, backup.study)
        assertEquals(settings, backup.settings)
        assertEquals(10, backup.manifest.dbVersion)
    }

    @Test
    fun theZipIsHumanReadable() {
        val bytes = write()
        val parts = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                parts[e.name] = zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        for (name in listOf("manifest.json", "content.json", "study.json", "settings.json")) {
            assertTrue("missing $name", parts.containsKey(name))
        }
        // Pretty-printed: the content part has newlines and indentation, so
        // a human can open it and read their bank.
        val content = parts.getValue("content.json")
        assertTrue(content.contains("\n  "))
        assertTrue(content.contains("\"title\": \"Paper\""))
    }

    @Test
    fun aForeignZipIsRefused() {
        val zip = java.io.ByteArrayOutputStream().let { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("unrelated.txt"))
                z.write("hello".toByteArray())
                z.closeEntry()
            }
            out.toByteArray()
        }
        val e = assertThrows(McqBackupFormat.Invalid::class.java) { McqBackupFormat.read(zip) }
        assertTrue(e.message!!.contains("not an mcqapp backup", ignoreCase = true))
    }

    @Test
    fun aFutureVersionIsRefusedWithGuidance() {
        val zip = write(
            manifest = McqBackupFormat.Manifest(
                format = McqBackupFormat.FORMAT,
                version = McqBackupFormat.VERSION + 1
            )
        )
        val e = assertThrows(McqBackupFormat.Invalid::class.java) { McqBackupFormat.read(zip) }
        assertTrue(e.message!!.contains("newer app version"))
    }

    @Test
    fun aTruncatedBackupNamesTheMissingPart() {
        val bytes = write()
        val stripped = java.io.ByteArrayOutputStream().let { out ->
            java.util.zip.ZipInputStream(bytes.inputStream()).use { input ->
                java.util.zip.ZipOutputStream(out).use { output ->
                    var e = input.nextEntry
                    while (e != null) {
                        if (e.name != McqBackupFormat.STUDY) {
                            output.putNextEntry(java.util.zip.ZipEntry(e.name))
                            output.write(input.readBytes())
                            output.closeEntry()
                        }
                        e = input.nextEntry
                    }
                }
            }
            out.toByteArray()
        }
        val e = assertThrows(McqBackupFormat.Invalid::class.java) { McqBackupFormat.read(stripped) }
        assertTrue(e.message!!.contains(McqBackupFormat.STUDY))
    }
}
