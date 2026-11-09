package com.mcqapp

import com.mcqapp.domain.TagNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact-tag index normalisation (stats-phase3.md §5): one function feeds
 * the migration backfill and every runtime writer, so they cannot disagree.
 */
class TagNormalizerTest {

    @Test
    fun `a csv splits on commas trims and folds`() {
        val labels = TagNormalizer.fromCsv("Java,  kotlin ,DART")
        assertEquals(listOf("java", "kotlin", "dart"), labels.map { it.folded })
        assertEquals(listOf("Java", "kotlin", "DART"), labels.map { it.tag })
    }

    @Test
    fun `blank parts are dropped`() {
        assertEquals(1, TagNormalizer.fromCsv(" , java , ,").size)
        assertEquals(0, TagNormalizer.fromCsv("").size)
        assertEquals(0, TagNormalizer.fromCsv(",, ,").size)
    }

    @Test
    fun `case variants dedupe to the first spelling`() {
        val labels = TagNormalizer.fromCsv("Java,JAVA,java, Kotlin")
        assertEquals(2, labels.size)
        assertEquals("Java", labels[0].tag)
        assertEquals("java", labels[0].folded)
    }

    @Test
    fun `non-ascii folds the way the search folds`() {
        val labels = TagNormalizer.fromCsv("CAFÉ, café")
        assertEquals(1, labels.size)
        assertEquals("café", labels[0].folded)
    }

    @Test
    fun `a list normalises like the csv of the same tags`() {
        val fromList = TagNormalizer.fromList(listOf("Java", "kotlin"))
        val fromCsv = TagNormalizer.fromCsv("Java,kotlin")
        assertEquals(fromCsv, fromList)
    }

    @Test
    fun `internal spaces survive trimming`() {
        val labels = TagNormalizer.fromCsv("machine learning,Machine Learning")
        assertEquals(1, labels.size)
        assertEquals("machine learning", labels[0].folded)
    }
}
