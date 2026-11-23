package com.mcqapp

import com.mcqapp.ui.search.searchCountLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The live result-count line has two forms: the exact total once the full
 * two-phase walk finished, and the bounded "N+" form while it is still
 * counting. The choice was inline in the composable, so neither branch had a
 * test and the "+" could disappear without one failing.
 */
class SearchCountLabelTest {

    @Test
    fun anExactCountUsesThePlainLabel() {
        assertEquals(R.plurals.search_result_count, searchCountLabelRes(exact = true))
        // A plural id is not a string id: the other branch must be a different
        // resource, or the "+" would never be rendered.
        assertNotEquals(R.plurals.search_result_count, searchCountLabelRes(exact = false))
    }

    @Test
    fun aBoundedCountUsesThePlusLabel() {
        assertEquals(R.plurals.search_result_count_more, searchCountLabelRes(exact = false))
        assertNotEquals(
            R.plurals.search_result_count_more,
            searchCountLabelRes(exact = true)
        )
    }
}
