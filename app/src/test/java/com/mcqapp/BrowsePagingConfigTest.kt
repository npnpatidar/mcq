package com.mcqapp

import com.mcqapp.ui.browse.BrowseViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browse list's paging window. Pure JVM: `PagingConfig` is a plain value
 * class, so this runs on every host.
 *
 * `jumpThreshold` defaults to `COUNT_UNDEFINED`, which disables jumps: a deep
 * link scrolled the list to a far index, but the source was then walked page by
 * page to reach it, and each page is a `LIMIT/OFFSET` query. These assertions
 * pin the setting that turns that walk into one reload around the target.
 */
class BrowsePagingConfigTest {

    private val config = BrowseViewModel.pagingConfig()

    @Test
    fun aDeepLinkCanJump() {
        // Positive, not COUNT_UNDEFINED (Int.MIN_VALUE): the regression.
        assertTrue("jumps must be on for a deep link", config.jumpThreshold > 0)
    }

    @Test
    fun anOrdinaryPageAppendStaysBelowTheJumpThreshold() {
        // A threshold at or under one page would invalidate on normal paging.
        assertTrue(config.jumpThreshold > config.pageSize)
    }

    @Test
    fun placeholdersGiveTheListThePapersLength() {
        // The deep-link index space is the paper's, not the window's.
        assertTrue(config.enablePlaceholders)
    }

    @Test
    fun theFirstLoadIsTwoPages() {
        assertEquals(config.pageSize * 2, config.initialLoadSize)
    }
}
