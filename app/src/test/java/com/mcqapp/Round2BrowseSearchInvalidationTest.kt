package com.mcqapp

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.mcqapp.domain.QuestionSearch
import com.mcqapp.ui.search.SearchInvalidationRelay
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B4 — search results must be invalidated after a write; the custom paging
 * source is not invalidated by Room. The relay holds the one live source and
 * invalidates it in place. A fake source counts the calls. Robolectric is
 * required because a real PagingSource.invalidate() logs through android.util.Log,
 * which throws "not mocked" in a plain JVM test (CI x86_64 runs this class).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Round2BrowseSearchInvalidationTest {

    private class CountingSource : PagingSource<Int, QuestionSearch.Hit>() {
        var invalidations = 0
            private set

        init {
            // PagingSource.invalidate() is final; callbacks are the public seam.
            registerInvalidatedCallback { invalidations++ }
        }

        override fun getRefreshKey(
            state: PagingState<Int, QuestionSearch.Hit>
        ): Int? = null

        override suspend fun load(
            params: LoadParams<Int>
        ): PagingSource.LoadResult<Int, QuestionSearch.Hit> =
            PagingSource.LoadResult.Page(emptyList<QuestionSearch.Hit>(), null, null)
    }

    @Test
    fun invalidatingCallsTheLiveSource() {
        val relay = SearchInvalidationRelay()
        val source = CountingSource()
        relay.liveSource = source
        relay.invalidate()
        assertEquals(1, source.invalidations)
    }

    @Test
    fun invalidatingWithNoSourceIsSafe() {
        val relay = SearchInvalidationRelay()
        // Before the first committed query there is no Pager/source yet.
        relay.invalidate()
        assertEquals(null, relay.liveSource)
    }

    @Test
    fun aBlankQueryClearsSourceSoStaleHitsAreNotInvalidated() {
        val relay = SearchInvalidationRelay()
        val old = CountingSource()
        relay.liveSource = old
        relay.liveSource = null
        relay.invalidate()
        assertEquals(0, old.invalidations)
    }

    @Test
    fun theNewestCommittedSourceReplacesTheOldOne() {
        val relay = SearchInvalidationRelay()
        val first = CountingSource()
        val second = CountingSource()
        relay.liveSource = first
        relay.liveSource = second // a new Pager was built for a new query
        relay.invalidate()
        assertEquals(0, first.invalidations)
        assertEquals(1, second.invalidations)
    }
}
