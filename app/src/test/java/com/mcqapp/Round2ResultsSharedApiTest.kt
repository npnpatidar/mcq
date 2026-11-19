package com.mcqapp

import com.mcqapp.data.export.ExportFormat
import com.mcqapp.domain.ReviewFilters
import com.mcqapp.domain.labelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2/R3 shared API: other agents compile against these resource ids, so the
 * mapping is pinned here. Pure JVM — resource ids are generated Int constants,
 * no Android runtime or resource loading is needed.
 */
class Round2ResultsSharedApiTest {

    @Test
    fun everyReviewFilterHasItsOwnLocalizedLabel() {
        val expected = mapOf(
            ReviewFilters.ALL to R.string.r2_res_filter_all,
            ReviewFilters.CORRECT to R.string.r2_res_filter_correct,
            ReviewFilters.WRONG to R.string.r2_res_filter_wrong,
            ReviewFilters.SKIPPED to R.string.r2_res_filter_skipped,
            ReviewFilters.UNGRADED to R.string.r2_res_filter_ungraded,
            ReviewFilters.SLOW to R.string.r2_res_filter_slow,
            ReviewFilters.SAVED to R.string.r2_res_filter_saved
        )
        expected.forEach { (key, resId) -> assertEquals(resId, key.labelRes) }
        // Negative: no two chips may collapse onto one label.
        assertEquals(expected.values.toSet().size, expected.size)
    }

    @Test
    fun unknownFilterFallsBackToAllInsteadOfAnInvalidResourceId() {
        assertEquals(ReviewFilters.ALL.labelRes, "not-a-filter".labelRes)
        assertNotEquals(0, "not-a-filter".labelRes)
    }

    @Test
    fun allEightExportFormatsCarryTitleAndDescriptionResources() {
        assertEquals(8, ExportFormat.entries.size)
        val titles = mutableSetOf<Int>()
        val descriptions = mutableSetOf<Int>()
        ExportFormat.entries.forEach { format ->
            // Positive: a real resource id was assigned in both locales.
            assertNotEquals("title missing for $format", 0, format.titleRes)
            assertNotEquals("description missing for $format", 0, format.descriptionRes)
            titles += format.titleRes
            descriptions += format.descriptionRes
            // The stored literals are unchanged for compatibility.
            assertTrue(format.title.isNotBlank())
            assertTrue(format.description.isNotBlank())
        }
        // Negative: no two formats may share a title or a description.
        assertEquals(ExportFormat.entries.size, titles.size)
        assertEquals(ExportFormat.entries.size, descriptions.size)
    }
}
