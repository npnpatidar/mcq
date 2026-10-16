package com.mcqapp

import com.mcqapp.data.io.scanImportPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * The bounded preview is the only thing the screen holds for a large bank, so
 * its counts and header must come from the same reader the import uses. These
 * are pure-JVM: no database, no Android harness.
 */
class ImportPreviewScannerTest {

    private fun scan(json: String, sample: Int = 500) =
        scanImportPreview({ ByteArrayInputStream(json.toByteArray()) }, sample)

    private fun bank(questionCount: Int, categories: Int = 1): String {
        val byCat = Array(categories) { mutableListOf<String>() }
        for (n in 1..questionCount) {
            val c = (n - 1) % categories
            byCat[c] += """{"id":"q$n","text":"Q$n","options":["a","b"],"correctOptionIds":["a"]}"""
        }
        val cats = byCat.mapIndexed { idx, qs ->
            val c = idx + 1
            """{"id":"c$c","title":"Cat $c","questions":[${qs.joinToString(",")}]}"""
        }.joinToString(",")
        return """{"papers":[{"id":"p1","title":"Big","description":"d","duration":90,"negative":0.25,"categories":[$cats]}]}"""
    }

    @Test
    fun readsHeaderCountsAndEffectiveCategory() {
        val preview = scan(bank(10, categories = 3))
        assertEquals("p1", preview.header?.id)
        assertEquals("Big", preview.header?.title)
        assertEquals(90, preview.header?.durationMinutes)
        assertEquals(0.25, preview.header?.negativeMarking ?: -1.0, 0.0)
        assertEquals(1, preview.paperCount)
        assertFalse(preview.isBackup)
        assertEquals(3, preview.firstPaperCategoryCount)
        assertEquals("Cat 1", preview.effectiveCategoryTitle)
        assertEquals(10, preview.totalQuestions)
        assertEquals(10, preview.sample.size)
    }

    @Test
    fun capsTheSampleWithoutLosingTheCount() {
        val preview = scan(bank(10), sample = 3)
        assertEquals(3, preview.sample.size)
        assertEquals(10, preview.totalQuestions)
        assertEquals(listOf("q1", "q2", "q3"), preview.sample.map { it.dto.id })
    }

    @Test
    fun flagsABackupBySecondPaperOrScheduling() {
        val twoPapers = """
        {"papers":[
          {"id":"p1","title":"One","categories":[{"id":"c","title":"C","questions":[
            {"id":"q1","text":"A","options":["a","b"],"correctOptionIds":["a"]}]}]},
          {"id":"p2","title":"Two","categories":[{"id":"c2","title":"C2","questions":[
            {"id":"q2","text":"B","options":["a","b"],"correctOptionIds":["a"]}]}]}
        ]}
        """.trimIndent()
        assertTrue(scan(twoPapers).isBackup)

        val scheduled = """
        {"papers":[{"id":"p1","title":"One","categories":[{"id":"c","title":"C","questions":[
          {"id":"q1","text":"A","options":["a","b"],"correctOptionIds":["a"]}]}]}],
         "scheduling":{"q1":{"ease":2.5,"intervalDays":1,"dueAt":1,"reps":1}}}
        """.trimIndent()
        assertTrue(scan(scheduled).isBackup)
    }

    @Test
    fun docxRootQuestionsAreNotABackup() {
        val json = """
        {"questions":[{"id":"d1","text":"A","options":["a","b"],"correctOptionIds":["a"]}]}
        """.trimIndent()
        val preview = scan(json)
        assertFalse(preview.isBackup)
        assertEquals(1, preview.paperCount)
        assertEquals(1, preview.totalQuestions)
        assertEquals("Imported Questions", preview.header?.title)
        assertTrue(preview.header?.id?.startsWith(
            com.mcqapp.data.io.LegacyParser.EPHEMERAL_PAPER_ID_PREFIX
        ) == true)
    }

    @Test
    fun anEmptyFileHasNoHeader() {
        val preview = scan("{}")
        assertNull(preview.header)
        assertEquals(0, preview.totalQuestions)
    }

    @Test
    fun anAllEmptyCategoryPaperFallsBackToUncategorized() {
        val json = """{"papers":[{"id":"p","title":"T","categories":[{"id":"c","title":"Empty"}]}]}"""
        assertEquals("Uncategorized", scan(json).effectiveCategoryTitle)
    }
}
